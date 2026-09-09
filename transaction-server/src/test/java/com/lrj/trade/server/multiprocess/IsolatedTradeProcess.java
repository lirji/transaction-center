package com.lrj.trade.server.multiprocess;

import com.lrj.trade.core.*;
import com.lrj.trade.jobs.*;
import com.lrj.trade.jobs.model.JobLease;
import com.lrj.trade.inventory.InventoryService;
import com.lrj.trade.server.TradeApplication;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 仅测试classpath的独立JVM入口；控制使用父进程stdin，不增加生产HTTP后门。 */
public final class IsolatedTradeProcess {
    /** 每个实例连接专属测试库，API和Worker角色有独立PID、连接池与Spring事务代理。 */
    public static void main(String[] args) throws Exception {
        if(!System.getenv().getOrDefault("TRADE_DB_URL","").contains("/transaction_center_multi_it?"))
            throw new IllegalStateException("多进程验证只允许专属测试库");
        Path output=Path.of(args[0]);Files.createDirectories(output);
        var json=JsonMapper.builder().build();
        try(var app=SpringApplication.run(TradeApplication.class,"--server.port=0","--spring.flyway.enabled=false")) {
            var jdbc=app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            if(!"transaction_center_multi_it".equals(jdbc.queryForObject("SELECT DATABASE()",String.class)))
                throw new IllegalStateException("数据库隔离不匹配");
            var jobs=app.getBean(JobLeaseService.class);
            var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));tx.setTimeout(10);
            JobLease held=null;
            Files.writeString(output.resolve("ready.tmp"),json.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),
                "port",((WebServerApplicationContext)app).getWebServer().getPort(),"role",args[1])));
            Files.move(output.resolve("ready.tmp"),output.resolve("ready.json"),StandardCopyOption.ATOMIC_MOVE);
            try(var input=new BufferedReader(new InputStreamReader(System.in))) {
                for(String line;(line=input.readLine())!=null;) {
                    var request=json.readTree(line);String id=request.get("id").asText();Object result;
                    try {
                        String action=request.get("action").asText();
                        if(action.equals("claim")) {
                            if(request.has("barrier")) {
                                Files.writeString(output.resolve(id+".waiting"),"{}");
                                long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                                while(!Files.exists(Path.of(request.get("barrier").asText()))) {
                                    if(System.nanoTime()>end)throw new IllegalStateException("领取屏障超时");Thread.sleep(10);
                                }
                            }
                            var leases=jobs.claim(900001,JobType.valueOf(request.get("type").asText()),args[1],1,request.get("leaseMillis").asLong());
                            held=leases.isEmpty()?null:leases.getFirst();result=held==null?Map.of("status","EMPTY"):Map.of("status","CLAIMED","lease",held);
                        } else if(action.equals("finish")) {
                            JobLease lease=held;
                            if(request.has("barrier")) {
                                Files.writeString(output.resolve("paused.json"),"{}");
                                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
                                while(!Files.exists(Path.of(request.get("barrier").asText()))) {
                                    if(System.nanoTime()>deadline)throw new IllegalStateException("有界屏障超时");Thread.sleep(20);
                                }
                            }
                            // 故意先写真实库存流水再fence；旧持有者必须整体回滚，而不是仅拒绝任务状态更新。
                            tx.executeWithoutResult(s->{
                                app.getBean(InventoryService.class).adjust(new Actor(900001,"urn:multi-instance","worker"),id,
                                    request.get("warehouse").asLong(),request.get("sku").asLong(),request.get("delta").asLong(),"隔离fencing验证");
                                jobs.complete(lease);
                            });result=Map.of("status","DONE");
                        } else if(action.equals("process")) {
                            result=Map.of("status",app.getBean(PaymentRecoveryWorker.class).processLease(held).name());
                        } else if(action.equals("batch")) {
                            result=Map.of("status","OK","outcomes",app.getBean(PaymentRecoveryWorker.class).runBatch(900001,args[1],1));
                        } else throw new IllegalArgumentException("未知测试指令");
                    } catch(TradeException e) {result=Map.of("status",e.code());}
                    Path temp=output.resolve(id+".tmp");Files.writeString(temp,json.writeValueAsString(result));
                    Files.move(temp,output.resolve(id+".json"),StandardCopyOption.ATOMIC_MOVE);
                }
            }
        }
    }
}
