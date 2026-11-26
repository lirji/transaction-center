package com.lrj.trade.server.console;

import com.lrj.trade.server.TradeApplication;
import com.lrj.trade.core.Checks;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 显式运行的真实MySQL→Spring HTTP→OIDC→浏览器联调，不使用JwtDecoder或API响应替身。 */
@Testcontainers
@SpringBootTest(classes=TradeApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleIntegrationIT {
    @Container static final MySQLContainer MYSQL=new MySQLContainer(System.getProperty("trade.test.mysql-image","mysql:8.4"))
        .withDatabaseName("transaction_center_console_it");
    static final int UI_PORT=freePort();
    static final ConsoleTestIdentity IDENTITY=identity();
    @Autowired ApplicationContext context;
    @LocalServerPort int port;
    private static int freePort() {try(var socket=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())) {return socket.getLocalPort();}catch(Exception e){throw new IllegalStateException(e);}}
    private static ConsoleTestIdentity identity() {try{return new ConsoleTestIdentity(UI_PORT);}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->MYSQL.getJdbcUrl()+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.flyway.user",MYSQL::getUsername);r.add("spring.flyway.password",MYSQL::getPassword);
        r.add("trade.security.issuer",IDENTITY::issuer);r.add("trade.security.jwk-set-uri",()->IDENTITY.issuer()+"/jwks");
        r.add("trade.security.audience",()->"transaction-center");
        r.add("trade.address.key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));r.add("trade.address.key-version",()->"isolated-test");
        r.add("trade.callback.active-key-version",()->"isolated-test");r.add("trade.callback.keys",()->"isolated-test:"+Base64.getEncoder().encodeToString(new byte[32]));
        r.add("trade.admin-evidence.enabled",()->"true");r.add("trade.admin-query.concurrency",()->"8");r.add("trade.admin-query.tenant-concurrency",()->"8");
        r.add("trade.admin-query.tenant-rate",()->"30");r.add("trade.fulfillment.enabled",()->"true");
        r.add("trade.fulfillment.allowed-return-actor-keys",()->Checks.hash(IDENTITY.issuer(),"admin")+","+Checks.hash(IDENTITY.issuer(),"diagnostic"));
        r.add("trade.fulfillment.allowed-delivery-actor-keys",()->Checks.hash(IDENTITY.issuer(),"admin"));
        r.add("trade.fulfillment.allowed-completion-actor-keys",()->Checks.hash(IDENTITY.issuer(),"admin"));
        r.add("trade.worker.enabled",()->"false");r.add("trade.outbox.enabled",()->"false");
    }
    /** 保持真实数据库与后端存活到浏览器退出，重复种子必须返回相同业务号。 */
    @Test void browserUsesPersistedBusinessDataAndRealJwt() throws Exception {
        var seed=new ConsoleBusinessSeed(context,IDENTITY.issuer());var manifest=seed.seed();assertEquals(manifest,seed.seed());
        verifySeedDatabase();
        String base="http://127.0.0.1:"+port;
        assertEquals(401,get(base,"orders",null));
        assertEquals(403,get(base,"orders",IDENTITY.accessToken("reader")));
        assertEquals(403,get(base,"operations/summary",IDENTITY.accessToken("reader")));
        assertEquals(403,get(base,"jobs",IDENTITY.accessToken("admin")));
        assertEquals(200,get(base,"jobs",IDENTITY.accessToken("diagnostic")));
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if(!Files.isDirectory(root.resolve("frontend"))) root=root.getParent();
        Path output=root.resolve("transaction-server/target/console-integration");Files.createDirectories(output);
        Path data=output.resolve("manifest.json");Files.writeString(data,JsonMapper.builder().build().writeValueAsString(manifest));
        var process=new ProcessBuilder("pnpm","exec","playwright","test","--config","playwright.integration.config.ts")
            .directory(root.resolve("frontend/apps/console").toFile()).redirectErrorStream(true)
            .redirectOutput(output.resolve("browser.log").toFile());
        process.environment().put("TRADE_E2E_BACKEND_URL",base);process.environment().put("TRADE_E2E_ISSUER",IDENTITY.issuer());
        process.environment().put("TRADE_E2E_UI_PORT",Integer.toString(UI_PORT));process.environment().put("TRADE_E2E_MANIFEST",data.toString());
        Process child=process.start();
        try {
            assertTrue(child.waitFor(5,TimeUnit.MINUTES),"浏览器联调超时，见target/console-integration/browser.log");
            assertEquals(0,child.exitValue(),"浏览器联调失败，见target/console-integration/browser.log");
            // 浏览器重试后实收与回库各只有两条，余额守恒，避免只验证HTTP成功。
            assertEquals(2,count("tc_return_receipt"));
            assertEquals(2,count("tc_inventory_return_operation"));
            var jdbc=context.getBean(JdbcTemplate.class);
            assertEquals(Map.of("stock_total",100L,"available",98L,"reserved",2L,"sold",0L),
                jdbc.queryForMap("SELECT stock_total,available,reserved,sold FROM tc_inventory_balance WHERE tenant_id=900001"));
        } finally {
            child.descendants().forEach(ProcessHandle::destroy);child.destroy();
        }
    }
    /** 只读SQL检查落库结果；种子写入仍全部由生产领域服务执行。 */
    private void verifySeedDatabase() {
        assertEquals(5,count("tc_order"));assertEquals(2,count("tc_payment_intent"));
        assertEquals(2,count("tc_payment_attempt"));assertEquals(3,count("tc_payment_receipt"));
        assertEquals(4,count("tc_refund"));assertEquals(1,count("tc_return_receipt"));
        var jdbc=context.getBean(JdbcTemplate.class);
        assertEquals(Set.of("PENDING_APPROVAL","REJECTED","SUCCEEDED","SUBMITTED"),
            new HashSet<>(jdbc.queryForList("SELECT status FROM tc_refund WHERE tenant_id=900001",String.class)));
    }
    private long count(String table) {
        return context.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=900001",Long.class);
    }
    private int get(String base,String resource,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+"/api/v1/trade/admin/"+resource)).GET();
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    @AfterAll static void closeIdentity() {IDENTITY.close();}
}
