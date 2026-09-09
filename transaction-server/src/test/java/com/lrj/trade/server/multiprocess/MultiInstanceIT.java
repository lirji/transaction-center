package com.lrj.trade.server.multiprocess;

import com.lrj.trade.core.*;
import com.lrj.trade.jobs.JobLeaseService;
import com.lrj.trade.server.TradeApplication;
import com.lrj.trade.server.console.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真正不同JVM的HTTP竞争、租约抢占和故障恢复；不改数据库时间、不触碰演示实例。 */
@Testcontainers
@SpringBootTest(classes=TradeApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiInstanceIT {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(System.getProperty("trade.test.mysql-image","mysql:8.4.11"))
        .withDatabaseName("transaction_center_multi_it");
    static final ConsoleTestIdentity IDENTITY=identity();
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    final List<Node> nodes=new CopyOnWriteArrayList<>();
    final Thread reaper=new Thread(()->nodes.forEach(n->{if(n.process.isAlive())n.process.destroyForcibly();}),"multi-test-child-cleanup");
    @BeforeEach void registerCleanup(){Runtime.getRuntime().addShutdownHook(reaper);}
    final List<Map<String,Object>> evidence=new ArrayList<>();
    final List<Double> latency=new ArrayList<>();
    final Path output=Path.of("target/multi-instance",UUID.randomUUID().toString()).toAbsolutePath();
    private static ConsoleTestIdentity identity(){try{return new ConsoleTestIdentity(1);}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        environment().forEach((k,v)->r.add(k,()->v));
        r.add("spring.datasource.url",()->MYSQL.getJdbcUrl()+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.flyway.user",MYSQL::getUsername);r.add("spring.flyway.password",MYSQL::getPassword);
    }
    private static Map<String,String> environment() {
        Map<String,String> env=new HashMap<>();
        env.put("TRADE_DB_URL",MYSQL.getJdbcUrl()+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        env.put("TRADE_DB_USER",MYSQL.getUsername());env.put("TRADE_DB_PASSWORD",MYSQL.getPassword());
        env.put("TRADE_JWT_ISSUER",IDENTITY.issuer());env.put("TRADE_JWT_JWKS",IDENTITY.issuer()+"/jwks");env.put("TRADE_JWT_AUDIENCE","transaction-center");
        env.put("TRADE_ADDRESS_KEY_BASE64",KEY);env.put("TRADE_ADDRESS_KEY_VERSION","multi-test");
        env.put("TRADE_CALLBACK_ACTIVE_KEY_VERSION","multi-test");env.put("TRADE_CALLBACK_KEYS","multi-test:"+KEY);
        for(String key:List.of("TRADE_WORKER_ENABLED","TRADE_OUTBOX_ENABLED","TRADE_OUTBOX_MAINTENANCE_ENABLED",
            "TRADE_REFUND_APPROVAL_ENABLED","TRADE_ORDER_ATTRIBUTION_ENABLED","TRADE_AUTOMATIC_ADMISSION_ENABLED",
            "TRADE_CATALOG_CACHE_ENABLED","TRADE_RECONCILIATION_QUERY_ENABLED"))env.put(key,"false");
        env.put("TRADE_BIND","127.0.0.1");
        env.put("TRADE_FULFILLMENT_ENABLED","true");
        for(String kind:List.of("DELIVERY","COMPLETION","RETURN"))env.put("TRADE_FULFILLMENT_ALLOWED_"+kind+"_ACTOR_KEYS",Checks.hash(IDENTITY.issuer(),"admin"));
        return env;
    }
    /** 一轮用例保留进程与业务关联，任何中间失败均清理本次创建的资源。 */
    @Test void multiProcessCorrectnessAndRecovery() throws Exception {
        Files.createDirectories(output);
        var data=new ConsoleBusinessSeed(context,IDENTITY.issuer()).seed();
        Node a=start("API-A"),b=start("API-B"),w1=start("Worker-A"),w2=start("Worker-B");
        assertEquals(4,nodes.stream().map(n->n.process.pid()).distinct().count());
        String member=IDENTITY.accessToken("member"),admin=IDENTITY.accessToken("admin");
        var quote=post(a,"/quotes","multi-quote",json.writeValueAsString(Map.of("items",List.of(Map.of("skuId",data.get("skuId"),"quantity",1)))),member);
        assertEquals(200,quote.statusCode());
        String body=json.writeValueAsString(Map.of("quoteId",json.readTree(quote.body()).get("quoteId").asText(),"warehouseId",data.get("warehouseId"),
            "address",Map.of("recipient","隔离收件人","phone","13800138000","address","隔离地址")));
        var first=CompletableFuture.supplyAsync(()->uncheckedPost(a,"/orders","multi-order",body,member));
        var second=CompletableFuture.supplyAsync(()->uncheckedPost(b,"/orders","multi-order",body,member));
        var ra=first.get(20,TimeUnit.SECONDS);var rb=second.get(20,TimeUnit.SECONDS);
        assertEquals(200,ra.statusCode(),ra.body());assertEquals(200,rb.statusCode(),rb.body());
        assertEquals(json.readTree(ra.body()).get("orderNo"),json.readTree(rb.body()).get("orderNo"));
        assertEquals(409,post(b,"/orders","multi-order",body.replace("隔离地址","不同地址"),member).statusCode());
        assertEquals(4,count("tc_order"));
        record("D-01","跨API订单同键同内容唯一、异内容409",Map.of("orders",4));
        String adjustment=json.writeValueAsString(Map.of("warehouseId",Long.parseLong(data.get("warehouseId")),"skuId",Long.parseLong(data.get("skuId")),"delta",5,"reason","跨API幂等"));
        var x=CompletableFuture.supplyAsync(()->uncheckedPost(a,"/admin/inventory-adjustments","multi-adjust",adjustment,admin));
        var y=CompletableFuture.supplyAsync(()->uncheckedPost(b,"/admin/inventory-adjustments","multi-adjust",adjustment,admin));
        assertEquals(x.get().body(),y.get().body());assertEquals(105,stock());
        assertEquals(404,get(b,"/admin/orders/"+data.get("paidOrder"),IDENTITY.accessToken("other")).statusCode());
        assertEquals(401,get(a,"/admin/orders",null).statusCode());
        record("D-17","跨进程鉴权与租户隔离",Map.of("unauthenticated",401,"crossTenant",404));

        enqueue("exclusive");
        Path gate=output.resolve("claim-gate");
        var race=Map.of("action","claim","type","RECON_QUERY","leaseMillis",30000,"barrier",gate.toString());
        String c1=w1.send(race);String c2=w2.send(race);
        await(()->Files.exists(w1.dir.resolve(c1+".waiting"))&&Files.exists(w2.dir.resolve(c2+".waiting")),10000);
        Files.writeString(gate,"start");
        var l1=w1.result(c1);var l2=w2.result(c2);
        assertEquals(Set.of("CLAIMED","EMPTY"),Set.of(l1.get("status").asText(),l2.get("status").asText()));
        Node winner=l1.get("status").asText().equals("CLAIMED")?w1:w2;
        assertEquals("DONE",finish(winner,data,1,null).get("status").asText());assertEquals(106,stock());
        record("D-13-exclusive","两个Worker进程只一个取得租约",Map.of("winners",1));

        enqueue("pause-takeover");
        JsonNode old=claim(w1,2000);assertEquals("CLAIMED",old.get("status").asText());
        Path release=output.resolve("release-old");
        String pending=w1.send(finishRequest(data,7,release));
        await(()->Files.exists(w1.dir.resolve("paused.json")),10000);
        signal(w1,"STOP");
        awaitExpired(old);
        JsonNode replacement=claim(w2,30000);assertTrue(token(replacement)>token(old));
        assertEquals(old.get("lease").get("jobId"),replacement.get("lease").get("jobId"));
        assertEquals("DONE",finish(w2,data,1,null).get("status").asText());
        Files.writeString(release,"release");signal(w1,"CONT");
        assertEquals("JOB_LEASE_LOST",w1.result(pending).get("status").asText());assertEquals(107,stock());
        record("D-13-stale","暂停/自然到期/接管/恢复后旧库存写入整体回滚",Map.of("oldToken",token(old),"newToken",token(replacement),"stock",107));

        enqueue("crash-takeover");old=claim(w1,2000);long killed=w1.process.pid();
        w1.process.destroyForcibly();assertTrue(w1.process.waitFor(10,TimeUnit.SECONDS));
        awaitExpired(old);replacement=claim(w2,30000);assertTrue(token(replacement)>token(old));
        assertEquals("DONE",finish(w2,data,1,null).get("status").asText());assertEquals(108,stock());
        Node restarted=start("Worker-A-restarted");assertNotEquals(killed,restarted.process.pid());
        assertEquals("EMPTY",claim(restarted,30000).get("status").asText());
        record("D-13-crash","强制退出后原任务接管、重启不重复完成",Map.of("killedPid",killed,"replacementPid",restarted.process.pid(),"stock",108));
        // 两个真实恢复Worker依次处理未决与已付任务：不可用风控不得创造资金成功。
        var batch1=w2.call(Map.of("action","batch"));var batch2=restarted.call(Map.of("action","batch"));
        assertEquals("RETRY",batch1.get("outcomes").get(0).asText());
        assertEquals("DONE",batch2.get("outcomes").get(0).asText());
        assertEquals(2,count("tc_payment_receipt"));
        assertEquals(108,stock());assertEquals(108,jdbc.queryForObject("SELECT available+reserved+sold FROM tc_inventory_balance WHERE tenant_id=900001",Integer.class));
        record("worker-recovery","生产PaymentRecoveryWorker处理持久任务；无新增资金事实",Map.of("receipts",2,"results",List.of(batch1,batch2)));
        // 模拟客户端未保留响应后API进程退出：另一新进程仍按原业务键恢复原订单。
        b.process.destroyForcibly();assertTrue(b.process.waitFor(10,TimeUnit.SECONDS));
        Node newApi=start("API-B-restarted");
        var replay=post(newApi,"/orders","multi-order",body,member);
        assertEquals(200,replay.statusCode());assertEquals(json.readTree(ra.body()).get("orderNo"),json.readTree(replay.body()).get("orderNo"));
        assertEquals(4,count("tc_order"));assertEquals(108,stock());
        record("D-01-restart","API退出后重试原业务号，订单及库存不重复",Map.of("orders",4,"stock",108));
        Collections.sort(latency);
        Files.writeString(output.resolve("result.json"),json.writeValueAsString(Map.of("status","PASS","database","transaction_center_multi_it",
            "instances",nodes.stream().map(n->Map.of("role",n.role,"pid",n.process.pid(),"port",n.port)).toList(),"cases",evidence,
            "httpSamples",latency.size(),"p50Millis",percentile(.5),"p95Millis",percentile(.95),"p99Millis",percentile(.99),
            "scope","correctness only; controlled worker batches, not production capacity or channel acceptance")));
    }
    private double percentile(double q){return latency.get(Math.max(0,(int)Math.ceil(q*latency.size())-1));}
    private HttpResponse<String> post(Node n,String path,String key,String body,String token) throws Exception {
        long start=System.nanoTime();var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(n.url()+"/api/v1/trade"+path)).timeout(Duration.ofSeconds(15))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        synchronized(latency){latency.add((System.nanoTime()-start)/1_000_000.0);}return response;
    }
    private HttpResponse<String> uncheckedPost(Node n,String p,String k,String b,String t){try{return post(n,p,k,b,t);}catch(Exception e){throw new CompletionException(e);}}
    private HttpResponse<String> get(Node n,String path,String token) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(n.url()+"/api/v1/trade"+path)).timeout(Duration.ofSeconds(10));if(token!=null)b.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void enqueue(String key){new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(s->context.getBean(JobLeaseService.class).enqueue(900001,JobType.RECON_QUERY,key));}
    private JsonNode claim(Node n,long millis)throws Exception{return n.call(Map.of("action","claim","type","RECON_QUERY","leaseMillis",millis));}
    private long token(JsonNode n){return n.get("lease").get("fencingToken").asLong();}
    private Map<String,Object> finishRequest(Map<String,String>d,int delta,Path barrier){var m=new HashMap<String,Object>();m.put("action","finish");m.put("warehouse",d.get("warehouseId"));m.put("sku",d.get("skuId"));m.put("delta",delta);if(barrier!=null)m.put("barrier",barrier.toString());return m;}
    private JsonNode finish(Node n,Map<String,String>d,int delta,Path barrier)throws Exception{return n.call(finishRequest(d,delta,barrier));}
    private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=900001",Long.class);}
    private int stock(){return jdbc.queryForObject("SELECT stock_total FROM tc_inventory_balance WHERE tenant_id=900001",Integer.class);}
    private void awaitExpired(JsonNode claim)throws Exception {
        String id=claim.get("lease").get("jobId").asText();
        await(()->Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_until<CURRENT_TIMESTAMP(3) FROM tc_job_lease WHERE tenant_id=900001 AND id=?",Boolean.class,id)),10000);
    }
    private void signal(Node n,String signal)throws Exception {assertTrue(n.process.isAlive());assertEquals(0,new ProcessBuilder("kill","-"+signal,Long.toString(n.process.pid())).start().waitFor());}
    /** 阶段采样只读数据库计数及专属容器资源；用于诊断而非生产容量承诺。 */
    private void record(String id,String description,Map<String,?> details)throws Exception {
        var db=jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_data_reads','Innodb_data_writes')");
        var resource=new ProcessBuilder("docker","stats","--no-stream","--format","{{json .}}",MYSQL.getContainerId()).redirectErrorStream(true).start();
        assertTrue(resource.waitFor(10,TimeUnit.SECONDS),"专属容器资源采样超时");
        assertEquals(0,resource.exitValue());
        var container=json.readTree(new String(resource.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        var cpus=nodes.stream().map(n->Map.of("pid",n.process.pid(),"alive",n.process.isAlive(),"cpuMillis",n.process.info().totalCpuDuration().map(Duration::toMillis).orElse(-1L))).toList();
        var queue=jdbc.queryForMap("SELECT COUNT(*) AS unfinished, COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,CURRENT_TIMESTAMP(3))),0) AS oldestAgeSeconds FROM tc_job_lease WHERE tenant_id=900001 AND status IN ('READY','RUNNING')");
        evidence.add(Map.of("id",id,"description",description,"details",details,"at",java.time.Instant.now().toString(),"databaseMetrics",db,"databaseContainer",container,"processCpu",cpus,"queue",queue));
        Files.writeString(output.resolve("cases.json"),json.writeValueAsString(evidence));
    }
    private static void await(BooleanSupplier ready,long millis)throws Exception {long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(!ready.getAsBoolean()){if(System.nanoTime()>end)fail("等待条件超时");Thread.sleep(25);}}
    private Node start(String role)throws Exception {
        Path dir=output.resolve(role);Files.createDirectories(dir);
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx256m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),IsolatedTradeProcess.class.getName(),dir.toString(),role);
        builder.environment().keySet().removeIf(k->k.startsWith("TRADE_")||k.startsWith("SPRING_"));builder.environment().putAll(environment());
        builder.redirectErrorStream(true).redirectOutput(dir.resolve("process.log").toFile());
        Node n=new Node(builder.start(),dir,role);nodes.add(n);
        await(()->Files.exists(dir.resolve("ready.json"))||!n.process.isAlive(),45000);assertTrue(n.process.isAlive(),"进程启动失败，见"+dir);
        n.port=json.readTree(Files.readString(dir.resolve("ready.json"))).get("port").asInt();return n;
    }
    private final class Node {
        final Process process;final Path dir;final String role;final BufferedWriter input;int port;
        Node(Process p,Path d,String r){process=p;dir=d;role=r;input=new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));}
        String url(){return "http://127.0.0.1:"+port;}
        String send(Map<String,?> body)throws Exception{var m=new HashMap<String,Object>(body);String id=UUID.randomUUID().toString();m.put("id",id);input.write(json.writeValueAsString(m));input.newLine();input.flush();return id;}
        JsonNode result(String id)throws Exception{await(()->Files.exists(dir.resolve(id+".json"))||!process.isAlive(),20000);assertTrue(process.isAlive(),"Worker退出，见"+dir);return json.readTree(Files.readString(dir.resolve(id+".json")));}
        JsonNode call(Map<String,?> body)throws Exception{return result(send(body));}
    }
    /** 即使断言失败或旧进程仍暂停，也只清理当前测试创建的PID。 */
    @AfterEach void cleanup()throws Exception{for(Node n:nodes){if(n.process.isAlive()){new ProcessBuilder("kill","-CONT",Long.toString(n.process.pid())).start().waitFor();n.process.destroy();if(!n.process.waitFor(5,TimeUnit.SECONDS))n.process.destroyForcibly().waitFor();}}Runtime.getRuntime().removeShutdownHook(reaper);}
    @AfterAll static void closeIdentity(){IDENTITY.close();}
}
