package com.lrj.trade.server.console;

import com.lrj.trade.core.Checks;
import com.lrj.trade.server.TradeApplication;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 显式演示数据工具，只允许固定本地开发库；不会随普通测试或生产启动执行。 */
@SpringBootTest(classes=TradeApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoBusinessSeedIT {
    private static final String ISSUER="urn:transaction-center:demo-business:v1";
    @Autowired ApplicationContext context;
    /** 在Spring创建数据源之前拒绝非开发目标，并关闭全部后台和外部入口。 */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(!"transaction_center_dev".equals(System.getenv("TRADE_DEMO_SEED_DATABASE"))
            || !System.getenv().getOrDefault("TRADE_DB_URL","").startsWith("jdbc:mysql://127.0.0.1:43306/transaction_center_dev?"))
            throw new IllegalStateException("请通过scripts/seed-demo-business.py指定本地演示库");
        r.add("spring.flyway.enabled",()->"false");r.add("spring.profiles.active",()->"");
        r.add("server.address",()->"127.0.0.1");
        for(String key:List.of("trade.worker.enabled","trade.outbox.enabled","trade.outbox.maintenance-enabled",
                "trade.refund-approval.enabled","trade.order-attribution.enabled","trade.automatic-admission.enabled")) r.add(key,()->"false");
        // 仅当前测试进程调用已有领域入口，不向运行中的API授予管理员履约权限。
        r.add("trade.fulfillment.enabled",()->"true");
        for(String kind:List.of("delivery","completion","return"))
            r.add("trade.fulfillment.allowed-"+kind+"-actor-keys",()->Checks.hash(ISSUER,"admin"));
    }
    /** 固定业务号清单让重复命令保留用户已修改的数据，而不是把它重置成初始状态。 */
    @Test void populatePersistentDemo() throws Exception {
        var jdbc=context.getBean(JdbcTemplate.class);
        assertEquals("transaction_center_dev",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(33,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
        Path file=Path.of(System.getenv("TRADE_DEMO_SEED_MANIFEST"));
        var json=JsonMapper.builder().build();
        if(Files.exists(file)) {
            var saved=json.readTree(Files.readString(file));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tc_order WHERE tenant_id=900001 AND order_no=?",
                Integer.class,saved.get("paidOrder").asText()),"清单与数据库不一致，请先核对，不自动重建数据");
            System.out.println("DEMO_SEED_EXISTS: 保留已有演示交易及用户操作");return;
        }
        var seed=new ConsoleBusinessSeed(context,ISSUER);
        var data=seed.seed();assertEquals(data,seed.seed());
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM tc_refund r JOIN tc_order o ON r.order_id=o.id AND r.tenant_id=o.tenant_id WHERE o.tenant_id=900001 AND o.order_no=?",Integer.class,data.get("paidOrder")));
        Files.createDirectories(file.getParent());
        Files.writeString(file,json.writeValueAsString(data),StandardOpenOption.CREATE_NEW);
        System.out.println("DEMO_SEED_CREATED: 3订单、2支付、2实收、4退款、2退货，重放校验通过");
    }
}
