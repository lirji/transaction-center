package com.lrj.trade.server;

import com.lrj.trade.inventory.model.Balance;

import com.lrj.trade.member.model.Consent;

import com.lrj.trade.catalog.CatalogService;
import com.lrj.trade.catalog.QuoteService;
import com.lrj.trade.catalog.model.QuoteSelection;
import com.lrj.trade.core.*;
import com.lrj.trade.inventory.InventoryService;
import com.lrj.trade.member.MemberService;
import com.lrj.trade.member.ConsentDocumentService;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 使用与dev_infra同版本的隔离MySQL，测试不得清空或中断公共实例。 */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "trade.security.issuer=https://identity.test", "trade.security.jwk-set-uri=https://identity.test/keys",
    "trade.security.audience=transaction-center"})
class FoundationTest {
    @org.springframework.beans.factory.annotation.Autowired
    com.lrj.trade.server.mapper.SeedDatabaseMapper seedDatabaseMapper;
    @Container static final MySQLContainer MYSQL=new MySQLContainer(System.getProperty("trade.test.mysql-image","mysql:8.4")).withDatabaseName("transaction_center_dev");
    private static final byte[] ADDRESS_TEST_KEY=new java.security.SecureRandom().generateSeed(32);
    private static final byte[] CALLBACK_TEST_KEY=new java.security.SecureRandom().generateSeed(32);
    private static final AtomicLong TENANTS=new AtomicLong(100);
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("trade.admin-evidence.enabled",()->"true");
        r.add("trade.admin-query.tenant-rate",()->"10000");
        r.add("trade.fulfillment.enabled",()->"true");
        r.add("trade.fulfillment.allowed-delivery-actor-keys",()->Checks.hash("https://identity.test","fulfillment-admin"));
        r.add("trade.fulfillment.allowed-completion-actor-keys",()->Checks.hash("https://identity.test","fulfillment-service"));
        r.add("trade.fulfillment.allowed-return-actor-keys",()->Checks.hash("https://identity.test","warehouse-admin"));
        r.add("trade.reconciliation.query-enabled",()->"true");
        r.add("trade.reconciliation.report-enabled",()->"true");
        r.add("trade.reconciliation.report-allowed-actor-keys",()->Checks.hash("https://identity.test","recon-reporter"));
        r.add("trade.reconciliation.enabled",()->"true");
        r.add("trade.reconciliation.allowed-actor-keys",()->Checks.hash("https://identity.test","recon-reader"));
        r.add("trade.evidence.enabled",()->"true");
        r.add("trade.evidence.allowed-actor-keys",()->Checks.hash("https://identity.test","evidence-reader"));
        r.add("spring.datasource.url",()->MYSQL.getJdbcUrl()+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        r.add("spring.datasource.username",MYSQL::getUsername);
        r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.flyway.user",MYSQL::getUsername);
        r.add("spring.flyway.password",MYSQL::getPassword);
        r.add("trade.address.key-base64",()->Base64.getEncoder().encodeToString(ADDRESS_TEST_KEY));
        r.add("trade.address.key-version",()->"test-v1");
        r.add("trade.callback.active-key-version",()->"test-v1");
        r.add("trade.callback.keys",()->"test-v1:"+Base64.getEncoder().encodeToString(CALLBACK_TEST_KEY));
    }
    /** 隔离MySQL验证永久凭证；验证器替身仅用于控制业务边界，RSA另有真实JWKS专项。 */
    @Test void attributionPermanentReplaySurvivesCommandEvictionAndKeyRetirement() {
        Fixture f=orderFixture(3); long q=attributionQuote(f,"one");
        var verifier=mock(com.lrj.trade.order.OrderAttributionVerifierPort.class);
        var claim=attributionClaim(f,q,"one");
        when(verifier.verify(f.actor(),Long.parseLong(members.me(f.actor()).memberId()),q,"signed-one")).thenReturn(claim);
        var service=attributionService(verifier,application.getBean(Outbox.class),application.getBean(com.lrj.trade.order.AddressProtector.class));
        var order=service.create(f.actor(),"attributed",q,f.warehouse(),shipping(),"signed-one");
        assertEquals(100,order.payableAmount());assertEquals(1,balance(f).reserved());
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",f.actor().tenantId());
        reset(verifier);when(verifier.verify(any(),anyLong(),anyLong(),anyString())).thenThrow(new AssertionError("历史重放不得再次验签"));
        when(clock.instant()).thenReturn(claim.expiresAt().plusSeconds(1));
        assertEquals(order,service.create(f.actor(),"attributed",q,f.warehouse(),shipping(),"signed-one"));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(TradeException.class,()->service.create(f.actor(),"attributed",q,f.warehouse(),shipping())).code());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(TradeException.class,()->service.create(f.actor(),"attributed",q,f.warehouse(),
            new com.lrj.trade.order.model.ShippingAddress("另一人","13800138000","另一个地址"),"signed-one")).code());
        verifyNoInteractions(verifier);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_order_attribution WHERE tenant_id=?",f.actor().tenantId()));
    }
    @Test void attributionSingleConsumptionRemainsAfterCancelAndRollsBackOnFailure() {
        Fixture f=orderFixture(3);long first=attributionQuote(f,"first"),second=attributionQuote(f,"second");
        var verifier=mock(com.lrj.trade.order.OrderAttributionVerifierPort.class);
        when(verifier.verify(any(),anyLong(),anyLong(),anyString())).thenAnswer(call->attributionClaim(f,call.getArgument(2),"same-jti"));
        var failure=mock(Outbox.class);doThrow(new IllegalStateException("模拟事件写入失败")).when(failure).append(anyLong(),anyString(),anyString());
        var protector=application.getBean(com.lrj.trade.order.AddressProtector.class);
        assertThrows(IllegalStateException.class,()->attributionService(verifier,failure,protector).create(f.actor(),"first",first,f.warehouse(),shipping(),"signed"));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_order_attribution WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_order WHERE tenant_id=?",f.actor().tenantId()));assertEquals(0,balance(f).reserved());
        var service=attributionService(verifier,application.getBean(Outbox.class),protector);
        var order=service.create(f.actor(),"first",first,f.warehouse(),shipping(),"signed");
        service.cancel(f.actor(),"cancel",order.orderNo(),"取消不释放归因身份");
        assertEquals("ORDER_ATTRIBUTION_CONSUMED",assertThrows(TradeException.class,()->service.create(f.actor(),"second",second,f.warehouse(),shipping(),"signed-again")).code());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_order WHERE tenant_id=?",f.actor().tenantId()));assertEquals(0,balance(f).reserved());
    }
    @Test void attributionRechecksTimeAfterEncryptionAndRejectsOuterTransactionBeforeVerification() {
        Fixture f=orderFixture(2);long q=attributionQuote(f,"time");var claim=attributionClaim(f,q,"time");
        var verifier=mock(com.lrj.trade.order.OrderAttributionVerifierPort.class);
        when(verifier.verify(any(),anyLong(),anyLong(),anyString())).thenReturn(claim);
        var real=application.getBean(com.lrj.trade.order.AddressProtector.class);
        var protector=mock(com.lrj.trade.order.AddressProtector.class);
        when(protector.encrypt(anyLong(),anyString(),any())).thenAnswer(call->{
            var encrypted=real.encrypt(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            when(clock.instant()).thenReturn(claim.expiresAt());return encrypted;
        });
        var service=attributionService(verifier,application.getBean(Outbox.class),protector);
        assertEquals("ORDER_ATTRIBUTION_TRANSACTION",assertThrows(TradeException.class,()->new TransactionTemplate(transactions).execute(tx->
            service.create(f.actor(),"time",q,f.warehouse(),shipping(),"signed"))).code());verifyNoInteractions(verifier);
        assertEquals("ORDER_ATTRIBUTION_UNTRUSTED",assertThrows(TradeException.class,()->service.create(f.actor(),"time",q,f.warehouse(),shipping(),"signed")).code());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_order_attribution WHERE tenant_id=?",f.actor().tenantId()));assertEquals(0,balance(f).reserved());
    }
    @Test void attributionConcurrentSameRequestCreatesOnePermanentSnapshot() throws Exception {
        Fixture f=orderFixture(3);long q=attributionQuote(f,"race");var claim=attributionClaim(f,q,"race");
        var verifier=mock(com.lrj.trade.order.OrderAttributionVerifierPort.class);
        when(verifier.verify(any(),anyLong(),anyLong(),anyString())).thenReturn(claim);
        var service=attributionService(verifier,application.getBean(Outbox.class),application.getBean(com.lrj.trade.order.AddressProtector.class));
        try(var pool=Executors.newFixedThreadPool(6)) {
            var start=new CountDownLatch(1);var results=new ArrayList<Future<String>>();
            for(int i=0;i<6;i++) results.add(pool.submit(()->{start.await();return service.create(f.actor(),"race",q,f.warehouse(),shipping(),"signed").orderNo();}));
            start.countDown();var numbers=new HashSet<String>();for(var result:results) numbers.add(result.get(20,TimeUnit.SECONDS));assertEquals(1,numbers.size());
        }
        assertEquals(1,count("SELECT COUNT(*) FROM tc_order_attribution WHERE tenant_id=?",f.actor().tenantId()));assertEquals(1,balance(f).reserved());
    }
    @Test void attributionDisabledDoesNotSilentlyDropTokenAndLegacyRequestStillWorks() {
        Fixture f=orderFixture(2);long q=attributionQuote(f,"disabled");
        assertEquals("ORDER_ATTRIBUTION_DISABLED",assertThrows(TradeException.class,()->orders.create(f.actor(),"disabled",q,f.warehouse(),shipping(),"signed")).code());
        var order=orders.create(f.actor(),"legacy",q,f.warehouse(),shipping());
        assertEquals(order,orders.create(f.actor(),"legacy",q,f.warehouse(),shipping()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_order_attribution WHERE tenant_id=?",f.actor().tenantId()));
    }
    @Test void attributionRealSignatureReachesDatabaseAndOtherActorCannotReplay() throws Exception {
        Fixture f=orderFixture(3);long q=attributionQuote(f,"rsa");
        try(var keys=new OrderAttributionVerifierTest.Fixture()) {
            String token=keys.token(Map.of("tenant_id",Long.toString(f.actor().tenantId()),"actor_key",f.actor().key(),
                "member_id",members.me(f.actor()).memberId(),"quote_id",Long.toString(q),
                "iat",Date.from(clock.instant()),"exp",Date.from(clock.instant().plusSeconds(60))));
            var verifier=OrderAttributionVerifier.create("https://marketing.test","http://127.0.0.1:"+keys.server.getAddress().getPort()+"/keys",clock);
            var service=attributionService(verifier,application.getBean(Outbox.class),application.getBean(com.lrj.trade.order.AddressProtector.class));
            var order=service.create(f.actor(),"rsa",q,f.warehouse(),shipping(),token);
            var other=new Actor(f.actor().tenantId(),f.actor().issuer(),"another-buyer");members.register(other,"另一会员");
            assertEquals("ORDER_ATTRIBUTION_UNTRUSTED",assertThrows(TradeException.class,()->service.create(other,"rsa",q,f.warehouse(),shipping(),token)).code());
            jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",f.actor().tenantId());
            Instant retired=clock.instant().plusSeconds(61);when(clock.instant()).thenReturn(retired);
            keys.server.stop(0);
            assertEquals(order,service.create(f.actor(),"rsa",q,f.warehouse(),shipping(),token));
            assertEquals(Checks.hash(token),jdbc.queryForObject("SELECT token_hash FROM tc_order_attribution WHERE tenant_id=?",String.class,f.actor().tenantId()));
        }
    }
    @Test void attributionMerchantLockWaitCannotExtendTokenLifetime() throws Exception {
        Fixture f=orderFixture(2);long q=attributionQuote(f,"locked");var claim=attributionClaim(f,q,"locked");
        var verifier=mock(com.lrj.trade.order.OrderAttributionVerifierPort.class);var verified=new CountDownLatch(1);
        when(verifier.verify(any(),anyLong(),anyLong(),anyString())).thenAnswer(call->{verified.countDown();return claim;});
        var service=attributionService(verifier,application.getBean(Outbox.class),application.getBean(com.lrj.trade.order.AddressProtector.class));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForObject("SELECT id FROM tc_merchant WHERE tenant_id=? FOR UPDATE",Long.class,f.actor().tenantId());locked.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("锁未释放");}catch(InterruptedException e){throw new IllegalStateException(e);}
            }));
            try {
                assertTrue(locked.await(5,TimeUnit.SECONDS));
                var waiter=pool.submit(()->service.create(f.actor(),"locked",q,f.warehouse(),shipping(),"signed"));
                assertTrue(verified.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->waiter.get(200,TimeUnit.MILLISECONDS));
                when(clock.instant()).thenReturn(claim.expiresAt());release.countDown();holder.get(5,TimeUnit.SECONDS);
                assertEquals("ORDER_ATTRIBUTION_UNTRUSTED",assertInstanceOf(TradeException.class,assertThrows(ExecutionException.class,()->waiter.get(5,TimeUnit.SECONDS)).getCause()).code());
            } finally {release.countDown();}
        }
        assertEquals(0,count("SELECT COUNT(*) FROM tc_order_attribution WHERE tenant_id=?",f.actor().tenantId()));assertEquals(0,balance(f).reserved());
    }
    @Test void attributionSameTokenCompetingKeysHaveOneWinner() throws Exception {
        Fixture f=orderFixture(3);long q=attributionQuote(f,"keys"),otherQuote=attributionQuote(f,"other-keys");
        var verifier=mock(com.lrj.trade.order.OrderAttributionVerifierPort.class);
        when(verifier.verify(any(),anyLong(),anyLong(),anyString())).thenAnswer(call->attributionClaim(f,call.getArgument(2),"shared-jti"));
        var service=attributionService(verifier,application.getBean(Outbox.class),application.getBean(com.lrj.trade.order.AddressProtector.class));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
            for(String key:List.of("key-a","key-b"))results.add(pool.submit(()->{start.await();try {
                service.create(f.actor(),key,key.equals("key-a")?q:otherQuote,f.warehouse(),shipping(),"signed");return true;
            } catch(TradeException rejected) {assertEquals("ORDER_ATTRIBUTION_CONSUMED",rejected.code());return false;}}));
            start.countDown();int won=0;for(var result:results)if(result.get(15,TimeUnit.SECONDS))won++;assertEquals(1,won);
        }
        assertEquals(1,count("SELECT COUNT(*) FROM tc_order WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_order_attribution WHERE tenant_id=?",f.actor().tenantId()));assertEquals(1,balance(f).reserved());
    }

    @Test void attributionLegacyOuterSnapshotCannotReuseEvictedSuccessfulKey() throws Exception {
        Fixture f=orderFixture(3);long q=attributionQuote(f,"snapshot");var claim=attributionClaim(f,q,"snapshot");
        var verifier=mock(com.lrj.trade.order.OrderAttributionVerifierPort.class);
        when(verifier.verify(any(),anyLong(),anyLong(),anyString())).thenReturn(claim);
        var service=attributionService(verifier,application.getBean(Outbox.class),application.getBean(com.lrj.trade.order.AddressProtector.class));
        var snapshot=new CountDownLatch(1);var committed=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var legacy=pool.submit(()->assertThrows(TradeException.class,()->new TransactionTemplate(transactions).execute(tx->{
                assertEquals(0,count("SELECT COUNT(*) FROM tc_order_attribution WHERE tenant_id=?",f.actor().tenantId()));snapshot.countDown();
                try {if(!committed.await(10,TimeUnit.SECONDS))throw new IllegalStateException("原请求未提交");}catch(InterruptedException e){throw new IllegalStateException(e);}
                return service.create(f.actor(),"snapshot",q,f.warehouse(),shipping());
            })).code());
            try {
                assertTrue(snapshot.await(5,TimeUnit.SECONDS));service.create(f.actor(),"snapshot",q,f.warehouse(),shipping(),"signed");
                jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",f.actor().tenantId());committed.countDown();
                assertEquals("IDEMPOTENCY_CONFLICT",legacy.get(10,TimeUnit.SECONDS));
            } finally {committed.countDown();}
        }
        assertEquals(1,count("SELECT COUNT(*) FROM tc_order WHERE tenant_id=?",f.actor().tenantId()));assertEquals(1,balance(f).reserved());
    }

    private long attributionQuote(Fixture f,String key) {
        return Long.parseLong(quotes.create(f.actor(),"attribution-"+key,List.of(new QuoteSelection(Long.toString(f.sku()),1L))).quoteId());
    }
    private com.lrj.trade.order.model.VerifiedOrderAttribution attributionClaim(Fixture f,long q,String jti) {
        return new com.lrj.trade.order.model.VerifiedOrderAttribution("https://marketing.test",jti,Long.toString(f.actor().tenantId()),f.actor().key(),
            members.me(f.actor()).memberId(),Long.toString(q),"ref","campaign",1,Checks.hash("signed"),clock.instant(),clock.instant().plusSeconds(60),null);
    }
    private com.lrj.trade.order.OrderService attributionService(com.lrj.trade.order.OrderAttributionVerifierPort verifier,Outbox outbox,com.lrj.trade.order.AddressProtector protector) {
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("verifier",verifier);
        return new com.lrj.trade.order.OrderService(application.getBean(com.lrj.trade.order.mapper.OrderMapper.class),application.getBean(Commands.class),quotes,
            application.getBean(com.lrj.trade.member.MemberTradingPort.class),application.getBean(com.lrj.trade.order.mapper.OrderAttributionMapper.class),application.getBean(com.lrj.trade.order.OrderAttributionReplayReader.class),
            beans.getBeanProvider(com.lrj.trade.order.OrderAttributionVerifierPort.class),inventory,merchants,protector,auditWriter,outbox,clock,
            application.getBean(AdmissionService.class),tradeFacts,jobLeases,900);
    }

    @Test void financialObservationSnapshotsMatchAuthoritativeStatesAndUseCoveringIndexes() {
        Fixture f=paymentFixture(2);long tenant=f.actor().tenantId();
        var payment=createPayment(f.actor(),"observe",factOrder(f,"observe").orderNo(),"TEST_A","return");
        jdbc.update("UPDATE tc_payment_attempt SET status='UNKNOWN',submitted_at='2001-01-01 00:00:00',updated_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND merchant_request_no=?",tenant,payment.activeAttemptNo());
        callbackInbox.accept(tenant,verifiedCallback(f,payment,"observe","observe-transaction",100));
        var refundFixture=paymentFixture(1);var plan=refundInboxPlan(refundFixture);
        refundInbox.receive(refundFixture.actor().tenantId(),refundEvidence(plan,"observe",100,"CNY",plan.providerTransactionNo()));
        var heldFixture=paymentFixture(1);requestRefund(heldFixture,paidRefundOrder(heldFixture,"observe",1),"observe",40);
        for(var source:application.getBeansOfType(FinancialSnapshotPort.class).values()) {
            var snapshot=source.snapshot();assertEquals(source.keys(),snapshot.rows().stream().map(FinancialSnapshotPort.Row::metric).toList());
            for(var row:snapshot.rows()) {
                String[] spec=switch(source.domain()+":"+row.metric()) {
                    case "payment:payment_unknown" -> new String[]{"tc_payment_attempt","status","UNKNOWN","submitted_at","ix_attempt_observation"};
                    case "payment:refund_held" -> new String[]{"tc_payment_refund_hold","state","HELD","created_at","ix_hold_observation"};
                    case "payment:refund_submitted" -> new String[]{"tc_payment_refund_hold","state","SUBMITTED","created_at","ix_hold_observation"};
                    case "payment:callback_pending" -> new String[]{"tc_payment_callback","status","PENDING","received_at","ix_payment_callback_observation"};
                    case "payment:callback_review" -> new String[]{"tc_payment_callback","status","REVIEW","received_at","ix_payment_callback_observation"};
                    case "refund:callback_pending" -> new String[]{"tc_refund_callback","status","PENDING","received_at","ix_refund_callback_observation"};
                    case "refund:callback_review" -> new String[]{"tc_refund_callback","status","REVIEW","received_at","ix_refund_callback_observation"};
                    case "reconciliation:difference_open" -> new String[]{"tc_reconciliation_case","status","OPEN","created_at","ix_recon_case_observation"};
                    case "reconciliation:difference_processing" -> new String[]{"tc_reconciliation_case","status","PROCESSING","created_at","ix_recon_case_observation"};
                    default -> throw new AssertionError(row.metric());
                };
                String where=" FROM "+spec[0]+" WHERE "+spec[1]+"='"+spec[2]+"'";
                assertEquals(Math.min(10001,jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class)),row.countLowerBound());
                var oldest=jdbc.queryForObject("SELECT MIN("+spec[3]+")"+where,java.sql.Timestamp.class);
                assertEquals(oldest==null?null:oldest.toInstant(),row.oldestAt());assertNotNull(row.observedAt());
                var explain=jdbc.queryForList("EXPLAIN SELECT "+spec[3]+" FROM "+spec[0]+" FORCE INDEX("+spec[4]+") WHERE "+spec[1]+"='"+spec[2]+"' ORDER BY "+spec[3]+",id LIMIT 10001").getFirst();
                assertEquals(spec[4],explain.get("key"));assertTrue(String.valueOf(explain.get("Extra")).contains("Using index"));assertFalse(String.valueOf(explain.get("Extra")).contains("filesort"));
            }
        }
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM tc_payment_attempt WHERE tenant_id=? AND merchant_request_no=?",String.class,tenant,payment.activeAttemptNo()));
        var source=application.getBean(com.lrj.trade.payment.PaymentFinancialSnapshotService.class);
        assertEquals(Instant.parse("2001-01-01T00:00:00Z"),source.snapshot().rows().getFirst().oldestAt());
    }
    @Test void financialObservationLargeBacklogIsCappedAndClosedCasesAreExcluded() {
        Fixture f=paymentFixture(1);long tenant=f.actor().tenantId();String original=queryCase(f,"observation-cap");
        var source=application.getBean(com.lrj.trade.core.ReconciliationFinancialSnapshotService.class);
        long before=source.snapshot().rows().getFirst().countLowerBound();
        // 只在隔离测试库写入10002条人工差异，验证有界查询而非全量COUNT。
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            var batch=new ArrayList<Object[]>();for(int i=0;i<10002;i++)batch.add(new Object[]{UUID.randomUUID().toString(),"cap-"+i,tenant,original});
            jdbc.batchUpdate("INSERT INTO tc_reconciliation_case(tenant_id,case_no,source_id,channel_id,statement_date,external_line_key,revision,difference_type,evidence_hash,evidence_ref,content_hash,status,created_at) SELECT tenant_id,?,source_id,channel_id,statement_date,?,revision,difference_type,evidence_hash,evidence_ref,content_hash,'OPEN','2000-01-01 00:00:00' FROM tc_reconciliation_case WHERE tenant_id=? AND case_no=?",batch);
        });
        var row=source.snapshot().rows().getFirst();assertEquals(10001,row.countLowerBound());assertEquals(Instant.parse("2000-01-01T00:00:00Z"),row.oldestAt());
        jdbc.update("UPDATE tc_reconciliation_case SET status='CLOSED' WHERE tenant_id=? AND external_line_key LIKE 'cap-%'",tenant);
        assertEquals(before,source.snapshot().rows().getFirst().countLowerBound());
    }
    @Test void adminVersionConditionsRejectMissingStaleAndWrongStateButPreserveReplay() throws Exception {
        Actor a=actor();long tenant=a.tenantId();jwt("version-admin","trade.admin",tenant);jwt("version-member","",tenant);
        long id=Long.parseLong(catalog.create(a,"version-product","version-product","商品","version-sku",100,"CNY"));
        String root="/api/v1/trade/admin/products/"+id;
        assertEquals("\"0\"",request("GET",root,"version-admin",null).headers().firstValue("ETag").orElseThrow());
        String zero="{\"expectedVersion\":0}";
        assertEquals(428,versionRequest("POST",root+":publish","version-admin",zero,"missing",null).statusCode());
        assertEquals(403,versionRequest("POST",root+":publish","version-member",zero,"denied",null).statusCode());
        for(String value:List.of("*","W/\"0\"","\"01\"","\"0\",\"1\"","\"9223372036854775808\""))
            assertEquals(400,versionRequest("POST",root+":publish","version-admin",zero,"bad",value).statusCode());
        assertEquals(412,versionRequest("POST",root+":publish","version-admin",zero,"mismatch","\"1\"").statusCode());
        var first=versionRequest("POST",root+":publish","version-admin",zero,"published","\"0\"");assertEquals(200,first.statusCode(),first.body());
        assertEquals(412,versionRequest("POST",root+":publish","version-admin",zero,"stale","\"0\"").statusCode());
        String one="{\"expectedVersion\":1}";
        assertEquals(409,versionRequest("POST",root+":publish","version-admin",one,"wrong-state","\"1\"").statusCode());
        String sku="{\"expectedVersion\":1,\"skuNo\":\"new\",\"unitPrice\":100,\"currency\":\"CNY\",\"stockType\":\"PHYSICAL\",\"specification\":{}}";
        assertEquals(409,versionRequest("POST",root+"/skus","version-admin",sku,"wrong-sku-state","\"1\"").statusCode());
        assertEquals(200,versionRequest("POST",root+":unpublish","version-admin",one,"unpublished","\"1\"").statusCode());
        assertEquals(first.body(),versionRequest("POST",root+":publish","version-admin",zero,"published","\"0\"").body());
        assertEquals("\"2\"",request("GET",root,"version-admin",null).headers().firstValue("ETag").orElseThrow());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_command WHERE tenant_id=? AND operation='product.publish'",tenant));
        assertEquals(404,versionRequest("POST","/api/v1/trade/admin/products/9223372036854775807:publish","version-admin",zero,"absent","\"0\"").statusCode());
        jwt("version-other","trade.admin",tenant+9999);assertEquals(404,versionRequest("POST",root+":publish","version-other",zero,"other","\"0\"").statusCode());
    }
    private HttpResponse<String> versionRequest(String method,String path,String token,String body,String key,String match) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key",key);
        if(match!=null)builder.header("If-Match",match);
        return HttpClient.newHttpClient().send(builder.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void traceHttpPersistsSameRootInEventAndJobAndWorkerRestoresCaller() throws Exception {
        Actor a=actor();long tenant=a.tenantId();jwt("trace-member","",tenant);
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/trade/members:register"))
            .header("Authorization","Bearer trace-member").header("Content-Type","application/json").header("traceparent",TraceContextTest.PARENT)
            .POST(HttpRequest.BodyPublishers.ofString("{\"displayName\":\"追踪测试\"}")).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());
        String returned=response.headers().firstValue("traceparent").orElseThrow();var context=TraceContext.parse(returned);
        assertEquals(TraceContext.parse(TraceContextTest.PARENT).traceId(),context.traceId());assertNotEquals(TraceContextTest.PARENT,returned);
        String payload=jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=?",String.class,tenant);var event=MemberEventCodec.decode(payload);
        assertEquals(context.uuid(),event.traceId());
        var lease=jobLeases.claim(tenant,JobType.OUTBOX_PUBLISH,"trace-worker",1,30000).getFirst();assertEquals(returned,lease.traceParent());
        EventPublisherPort network=mock(EventPublisherPort.class);when(network.enabled()).thenReturn(true);
        when(network.publish(any())).thenAnswer(invocation->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(context.traceId(),TraceContext.current().traceId());assertNotEquals(context.spanId(),TraceContext.current().spanId());
            var message=invocation.getArgument(0,OutboxEventCatalog.Message.class);assertEquals(payload,message.payload());assertEquals(event.traceId(),message.traceId());
            return new EventPublisherPort.Receipt(message.eventId(),"trace-verification",0,1);
        });
        try(var outer=TraceContext.open(null)) {
            var caller=TraceContext.current();assertEquals(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.DONE,outboxWorker(network,application.getBean(OutboxDeliveryPort.class)).processLease(lease));assertSame(caller,TraceContext.current());
        }
        assertNull(TraceContext.current());assertEquals(payload,jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        var denied=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/trade/admin/products")).header("Authorization","Bearer trace-member").header("traceparent",TraceContextTest.PARENT).GET().build();
        assertEquals(403,HttpClient.newHttpClient().send(denied,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void traceFirstEnqueueSurvivesDuplicateWakeRenewAndTakeoverAndRollsBackAtomically() {
        long tenant=TENANTS.incrementAndGet();var tx=new TransactionTemplate(transactions);String first;
        try(var scope=TraceContext.open(TraceContextTest.PARENT)) {
            first=TraceContext.currentParent();tx.executeWithoutResult(t->jobLeases.schedule(tenant,JobType.ORDER_RESOLVE,"trace-job",Instant.now().minusSeconds(1)));
        }
        try(var other=TraceContext.open(null)) {
            tx.executeWithoutResult(t->{jobLeases.enqueue(tenant,JobType.ORDER_RESOLVE,"trace-job");jobLeases.schedule(tenant,JobType.ORDER_RESOLVE,"trace-job",Instant.now().plusSeconds(3600));jobLeases.wake(tenant,JobType.ORDER_RESOLVE,"trace-job");});
            assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(t->{jobLeases.enqueue(tenant,JobType.ORDER_RESOLVE,"trace-rollback");throw new IllegalStateException("rollback");}));
        }
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND business_key='trace-rollback'",tenant));
        var lease=jobLeases.claim(tenant,JobType.ORDER_RESOLVE,"trace-owner",1,30000).getFirst();assertEquals(first,lease.traceParent());
        assertEquals(first,jobLeases.renew(lease,30000).traceParent());expireLease(lease);
        var takeover=jobLeases.claim(tenant,JobType.ORDER_RESOLVE,"trace-takeover",1,30000).getFirst();assertEquals(first,takeover.traceParent());assertTrue(takeover.fencingToken()>lease.fencingToken());
        assertMysqlConstraint(3819,()->jdbc.update("UPDATE tc_job_lease SET trace_parent='00-00000000000000000000000000000000-00f067aa0ba902b7-00' WHERE tenant_id=?",tenant));
        assertMysqlConstraint(3819,()->jdbc.update("UPDATE tc_job_lease SET trace_parent='00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-00' WHERE tenant_id=?",tenant));
    }
    @Test void traceLegacyNullJobGetsFreshRootsAcrossRetriesAndNoThreadLeak() {
        Actor a=actor();members.register(a,"trace-legacy");long tenant=a.tenantId();
        assertNull(jdbc.queryForObject("SELECT trace_parent FROM tc_job_lease WHERE tenant_id=?",String.class,tenant));
        EventPublisherPort network=mock(EventPublisherPort.class);when(network.enabled()).thenReturn(true);var roots=new ArrayList<String>();
        when(network.publish(any())).thenAnswer(invocation->{roots.add(TraceContext.current().traceId());throw new IllegalStateException("uncertain");});
        var worker=outboxWorker(network,application.getBean(OutboxDeliveryPort.class));
        assertCode("INVALID_ARGUMENT",()->worker.processLease(null));assertNull(TraceContext.current());
        for(int i=0;i<2;i++) {
            var lease=jobLeases.claim(tenant,JobType.OUTBOX_PUBLISH,"legacy-"+i,1,30000).getFirst();assertNull(lease.traceParent());
            assertEquals(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.RETRY,worker.processLease(lease));assertNull(TraceContext.current());assertNull(org.slf4j.MDC.get("traceId"));
            jdbc.update("UPDATE tc_job_lease SET next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND status='READY'",tenant);
        }
        assertEquals(2,roots.stream().distinct().count());assertNull(jdbc.queryForObject("SELECT trace_parent FROM tc_job_lease WHERE tenant_id=?",String.class,tenant));
    }
    @Autowired MemberService members;
    @Autowired com.lrj.trade.member.MemberTradeFactPort tradeFacts;
    @Autowired ConsentDocumentService documents;
    @Autowired CatalogService catalog;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean RedisCatalogCache displayCache;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.lrj.trade.member.mapper.MemberMapper memberMapper;
    @Autowired com.lrj.trade.catalog.SkuService skus;
    @Autowired org.apache.ibatis.session.SqlSessionFactory sqlSessions;
    @Autowired QuoteService quotes;
    @Autowired com.lrj.trade.order.OrderService orders;
    @Autowired com.lrj.trade.order.MerchantService merchants;
    @Autowired com.lrj.trade.payment.ChannelConfigurationService paymentChannels;
    @Autowired com.lrj.trade.payment.PaymentService payments;
    @Autowired com.lrj.trade.payment.PaymentDispatchService paymentDispatch;
    @Autowired com.lrj.trade.payment.PaymentObservationPort paymentObservations;
    @Autowired com.lrj.trade.payment.PaymentRiskPort paymentRisks;
    @Autowired com.lrj.trade.payment.PaymentRefundPort refundFunds;
    @Autowired com.lrj.trade.refund.RefundService refunds;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.lrj.trade.refund.RefundPolicyService refundPolicies;
    @MockitoBean java.time.Clock clock;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AutomaticAdmissionGate automaticGate;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JwtDecoder decoder;
    // 仅HTTP编排夹具启用验证器替身，真实RSA认证由独立VerifierTest覆盖，生产缺省无该Bean。
    @MockitoBean RefundApprovalVerifier approvalVerifier;
    @LocalServerPort int port;

    @BeforeEach void fixedBusinessClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-07T08:00:00.123Z"));
    }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.lrj.trade.jobs.JobLeaseService jobLeases;

    @Autowired com.lrj.trade.payment.PaymentCallbackService callbackInbox;
    @Autowired com.lrj.trade.jobs.PaymentCallbackWorker callbackWorker;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.lrj.trade.payment.CallbackPayloadProtector callbackProtector;



    @Autowired com.lrj.trade.jobs.OrderResolutionWorker orderResolver;
    @Autowired com.lrj.trade.payment.OrderResolutionPort orderResolution;
    @Autowired com.lrj.trade.order.OrderResolutionBackfillService orderBackfill;

    @Autowired com.lrj.trade.jobs.JobTenantDiscovery tenantDiscovery;
    @Autowired org.springframework.context.ApplicationContext application;

    @Autowired com.lrj.trade.jobs.RefundRecoveryWorker refundRecoveryWorker;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.lrj.trade.refund.RefundApprovalReceiptService approvalReceipts;

    @Autowired RefundApprovalRejectionAudit rejectionAudit;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AuditWriter auditWriter;

    @Test void catalogProtectionStaleDisplayCannotAuthorizeNewQuoteOrOrder() throws Exception {
        Fixture f=paymentFixture(2);var product=catalog.list(f.actor().tenantId(),0,10,false).getFirst();
        var quote=quotes.create(f.actor(),"before-unpublish",List.of(new QuoteSelection(Long.toString(f.sku()),1L)));
        catalog.unpublish(f.actor(),"unpublish",Long.parseLong(product.productId()),product.version());
        doReturn(new com.lrj.trade.catalog.CatalogCachePort.Probe(UUID.randomUUID().toString(),new com.lrj.trade.catalog.CatalogCachePort.Value(List.of(product),List.of()),true)).when(displayCache).lookup(any());
        try {
            jwt("tester","",f.actor().tenantId());var display=request("GET","/api/v1/trade/products/"+product.productId(),"tester",null);assertEquals(200,display.statusCode(),display.body());
            assertCode("NOT_FOUND",()->quotes.create(f.actor(),"after-unpublish",List.of(new QuoteSelection(Long.toString(f.sku()),1L))));
            assertCode("NOT_FOUND",()->orders.create(f.actor(),"old-quote",Long.parseLong(quote.quoteId()),f.warehouse(),shipping()));
            assertEquals(0,count("SELECT COUNT(*) FROM tc_order WHERE tenant_id=?",f.actor().tenantId()));assertEquals(2,balance(f).available());
        } finally {reset(displayCache);}
    }

    @Test void hotSkuAdmissionSeparatesRequiresNewAndReleasesAfterOuterCompletion() {
        var guard=new com.lrj.trade.inventory.HotSkuAdmission(1,1);var outer=new TransactionTemplate(transactions);var inner=new TransactionTemplate(transactions);inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outer.executeWithoutResult(status->{
            guard.acquire(1,1,1);guard.acquire(1,1,1);assertEquals(1,guard.trackedKeys());
            assertCode("HOT_SKU_BUSY",()->inner.executeWithoutResult(s->guard.acquire(1,1,1)));
            assertCode("HOT_SKU_BUSY",()->inner.executeWithoutResult(s->guard.acquire(1,1,2)));
            guard.acquire(1,1,1);assertEquals(1,guard.trackedKeys());
        });assertEquals(0,guard.trackedKeys());
        outer.executeWithoutResult(status->{guard.acquire(1,1,2);status.setRollbackOnly();});assertEquals(0,guard.trackedKeys());
    }
    @Test void hotSkuAdmissionDoesNotBlockConfirmedOrReliablyReleasedReservations() {
        Fixture f=paymentFixture(2);var guard=new com.lrj.trade.inventory.HotSkuAdmission(1,2);
        var limited=new com.lrj.trade.inventory.InventoryService(application.getBean(com.lrj.trade.inventory.mapper.InventoryMapper.class),application.getBean(Commands.class),application.getBean(com.lrj.trade.catalog.CatalogPort.class),application.getBean(Outbox.class),application.getBean(com.lrj.trade.inventory.InventoryEventWriter.class),guard);
        limited.reserve(f.actor(),"hot-one",f.warehouse(),f.sku(),1);limited.reserve(f.actor(),"hot-two",f.warehouse(),f.sku(),1);
        var inner=new TransactionTemplate(transactions);inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            guard.acquire(f.actor().tenantId(),f.warehouse(),f.sku());
            assertNotNull(inner.execute(s->limited.confirm(f.actor(),"hot-confirm","hot-one")));
            assertNotNull(inner.execute(s->limited.release(f.actor(),"hot-release","hot-two")));assertEquals(1,guard.trackedKeys());
        });assertEquals(0,guard.trackedKeys());assertEquals(1,balance(f).available());assertEquals(1,balance(f).sold());
    }

    @Test void fulfillmentIngressServiceOrderRequiresTrustedCompletion() {
        Actor actor=actor();members.register(actor,"服务会员");merchants.create(actor,"merchant","logical","商户");
        long product=Long.parseLong(catalog.create(actor,"p","p","服务","s",0,"CNY"));long sku=Long.parseLong(catalog.detail(actor.tenantId(),product,true).skuId());
        skus.edit(actor,"service",product,sku,0,0,"CNY","SERVICE",Map.of());catalog.publish(actor,"publish",product,1);
        var quote=quotes.create(actor,"quote",List.of(new QuoteSelection(Long.toString(sku),1L)));var order=orders.create(actor,"order",Long.parseLong(quote.quoteId()),null,null);
        var service=application.getBean(com.lrj.trade.order.FulfillmentIngressService.class);service.deliver(actor,"deliver",order.orderNo(),"service-delivery","proof");
        assertCode("FULFILLMENT_INVALID",()->service.confirm(actor,"member-confirm",order.orderNo()));
        assertEquals("COMPLETED",service.complete(new Actor(actor.tenantId(),"https://identity.test","fulfillment-service"),"complete",Long.parseLong(order.orderId()),"service-complete","proof").status());
        assertFalse(members.me(actor).everPaid());assertEquals(0,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",actor.tenantId()));
    }
    @Test void fulfillmentIngressMemberAndServiceCompletionRaceKeepsOneFact() throws Exception {
        Fixture f=paymentFixture(1);var order=paidRefundOrder(f,"ingress-race",1);var service=application.getBean(com.lrj.trade.order.FulfillmentIngressService.class);
        service.deliver(f.actor(),"deliver",order.orderNo(),"delivery","proof");var source=new Actor(f.actor().tenantId(),"https://identity.test","fulfillment-service");var sequence=new AtomicLong();
        var results=parallel(20,()->{long i=sequence.incrementAndGet();return i%2==0?service.confirm(f.actor(),"confirm-"+i,order.orderNo()):service.complete(source,"service-"+i,Long.parseLong(order.orderId()),"event-"+i,"proof");});
        assertEquals(1,results.stream().distinct().count());assertEquals("COMPLETED",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-completed.v1'",f.actor().tenantId()));
    }
    @Test void fulfillmentIngressDifferentRmasCannotExceedActualReturnBudget() throws Exception {
        Fixture f=paymentFixture(2);var order=paidRefundOrder(f,"ingress-budget",2);var service=application.getBean(com.lrj.trade.order.FulfillmentIngressService.class);service.deliver(f.actor(),"deliver",order.orderNo(),"delivery","proof");
        long item=Long.parseLong(order.items().getFirst().itemId());var one=service.createReturn(f.actor(),"one",item,f.warehouse(),2);var two=service.createReturn(f.actor(),"two",item,f.warehouse(),2);var sequence=new AtomicLong();
        var outcomes=parallel(2,()->{String number=sequence.incrementAndGet()==1?one.resourceNo():two.resourceNo();try {service.inspect(f.actor(),number,number,0,"ACCEPTED",2,"proof");return true;}catch(TradeException error){assertEquals("RETURN_INVALID",error.code());return false;}});
        assertEquals(1,outcomes.stream().filter(Boolean::booleanValue).count());assertEquals(2,balance(f).available());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",f.actor().tenantId()));assertEquals(1,count("SELECT COUNT(*) FROM tc_return_request WHERE tenant_id=? AND status='OPEN'",f.actor().tenantId()));
    }
    @Test void fulfillmentIngressInspectionAuditRollsBackInventoryAndBudget() {
        Fixture f=paymentFixture(1);var order=paidRefundOrder(f,"ingress-inspection-audit",1);var service=application.getBean(com.lrj.trade.order.FulfillmentIngressService.class);service.deliver(f.actor(),"deliver",order.orderNo(),"delivery","proof");
        var rma=service.createReturn(f.actor(),"rma",Long.parseLong(order.items().getFirst().itemId()),f.warehouse(),1);long before=balance(f).available();
        var target=org.springframework.test.util.AopTestUtils.<AuditWriter>getUltimateTargetObject(auditWriter);
        doThrow(new IllegalStateException("audit failed")).when(target).append(any(),eq("fulfillment.ingress.RETURN_INSPECT"),anyString(),anyString(),anyString(),anyLong(),anyLong());
        try {assertThrows(IllegalStateException.class,()->service.inspect(f.actor(),"inspect",rma.resourceNo(),0,"ACCEPTED",1,"proof"));}finally{reset(target);}
        assertEquals(before,balance(f).available());assertEquals("OPEN",service.readReturn(f.actor().tenantId(),rma.resourceNo()).status());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals("INSPECTED",service.inspect(f.actor(),"inspect",rma.resourceNo(),0,"ACCEPTED",1,"proof").status());
    }
    @Test void fulfillmentIngressReturnHttpHasExplicitIdsAndVersionGuards() throws Exception {
        Fixture f=paymentFixture(1);var order=paidRefundOrder(f,"ingress-return-http",1);var service=application.getBean(com.lrj.trade.order.FulfillmentIngressService.class);service.deliver(f.actor(),"deliver",order.orderNo(),"delivery","proof");
        String body="{\"orderItemId\":\""+order.items().getFirst().itemId()+"\",\"warehouseId\":\""+f.warehouse()+"\",\"quantity\":1}";
        jwt("warehouse-admin","trade.admin",f.actor().tenantId());assertEquals(403,request("POST","/api/v1/trade/admin/returns","warehouse-admin",body,"rma").statusCode());
        jwt("untrusted","trade.admin trade.return.write",f.actor().tenantId());assertEquals(403,request("POST","/api/v1/trade/admin/returns","untrusted",body,"rma").statusCode());
        jwt("warehouse-admin","trade.admin trade.return.write",f.actor().tenantId());var created=request("POST","/api/v1/trade/admin/returns","warehouse-admin",body,"rma");assertEquals(201,created.statusCode(),created.body());var json=tools.jackson.databind.json.JsonMapper.builder().build();String number=json.readTree(created.body()).path("resourceNo").asText();
        assertTrue(number.matches("TRD-RTN-[0-9]{6}-[0-9]{10}"));
        String inspection="{\"result\":\"ACCEPTED\",\"restockQuantity\":1,\"evidenceRef\":\"proof\"}";
        var detail=request("GET","/api/v1/trade/admin/returns/"+number,"warehouse-admin",null);assertEquals("\"0\"",detail.headers().firstValue("ETag").orElseThrow());
        assertEquals(428,fulfillmentInspectHttp(number,inspection,null,"inspect").statusCode());assertEquals(412,fulfillmentInspectHttp(number,inspection,"\"9\"","stale").statusCode());
        var inspected=fulfillmentInspectHttp(number,inspection,"\"0\"","inspect");assertEquals(200,inspected.statusCode(),inspected.body());var result=json.readTree(inspected.body());assertEquals(number,result.path("returnNo").asText());assertTrue(result.path("receiptNo").asText().startsWith("RT-"));assertEquals(1,result.path("version").asLong());
        assertEquals(inspected.body(),fulfillmentInspectHttp(number,inspection,"\"0\"","inspect").body());
        var inspectedDetail=request("GET","/api/v1/trade/admin/returns/"+number,"warehouse-admin",null);
        assertTrue(json.readTree(inspectedDetail.body()).path("inspection").path("businessNo").asText()
            .matches("TRD-RMA-[0-9]{6}-[0-9]{10}"));
        jwt("warehouse-admin","trade.admin trade.return.write",f.actor().tenantId()+10000);assertEquals(404,request("GET","/api/v1/trade/admin/returns/"+number,"warehouse-admin",null).statusCode());
    }
    private HttpResponse<String> fulfillmentInspectHttp(String number,String body,String version,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/trade/admin/returns/"+number+":inspect")).header("Authorization","Bearer warehouse-admin").header("Idempotency-Key",key).header("Content-Type","application/json");
        if(version!=null) builder.header("If-Match",version);
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void fulfillmentIngressPermanentSourceSurvivesCacheAndDifferentHttpKeys() throws Exception {
        Fixture f=paymentFixture(2);var order=paidRefundOrder(f,"ingress-source",1);var service=application.getBean(com.lrj.trade.order.FulfillmentIngressService.class);
        var sequence=new AtomicLong();var results=parallel(20,()->service.deliver(f.actor(),"http-"+sequence.incrementAndGet(),order.orderNo(),"source-delivery","proof"));
        assertEquals(1,results.stream().distinct().count());
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",f.actor().tenantId());
        Instant later=clock.instant().plusSeconds(30);when(clock.instant()).thenReturn(later);
        assertEquals(results.getFirst(),service.deliver(f.actor(),"after-cache",order.orderNo(),"source-delivery","proof"));
        assertCode("FULFILLMENT_CONFLICT",()->service.deliver(f.actor(),"changed",order.orderNo(),"source-delivery","different"));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_order_fulfillment WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_fulfillment_ingress WHERE tenant_id=?",f.actor().tenantId()));
        var confirmed=service.confirm(f.actor(),"confirm",order.orderNo());assertEquals("COMPLETED",confirmed.status());
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",f.actor().tenantId());assertEquals(confirmed,service.confirm(f.actor(),"confirm-again",order.orderNo()));
        assertEquals(confirmed,service.complete(new Actor(f.actor().tenantId(),"https://identity.test","fulfillment-service"),"service",Long.parseLong(order.orderId()),"service-event","service-proof"));
    }
    @Test void fulfillmentIngressMemberNeedsOwnActualDelivery() {
        Fixture f=paymentFixture(2);var order=paidRefundOrder(f,"ingress-member",1);var service=application.getBean(com.lrj.trade.order.FulfillmentIngressService.class);
        assertCode("FULFILLMENT_INVALID",()->service.confirm(f.actor(),"early",order.orderNo()));
        service.deliver(f.actor(),"delivery",order.orderNo(),"delivery","proof");
        var other=new Actor(f.actor().tenantId(),"https://identity.test","other-member");members.register(other,"其他会员");
        assertCode("NOT_FOUND",()->service.confirm(other,"foreign",order.orderNo()));
        application.getBean(AdmissionService.class).update(f.actor(),"pause",0,false,false,"暂停新增");
        assertEquals("COMPLETED",service.confirm(f.actor(),"confirm",order.orderNo()).status());
    }
    @Test void fulfillmentIngressReturnRequestIsNotReceiptAndInspectionIsPermanent() throws Exception {
        Fixture f=paymentFixture(3);var order=paidRefundOrder(f,"ingress-return",2);var service=application.getBean(com.lrj.trade.order.FulfillmentIngressService.class);
        service.deliver(f.actor(),"deliver",order.orderNo(),"delivery","proof");
        long before=balance(f).available();var created=service.createReturn(f.actor(),"rma",Long.parseLong(order.items().getFirst().itemId()),f.warehouse(),2);
        assertEquals(before,balance(f).available());assertEquals(0,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",f.actor().tenantId()));
        var results=parallel(20,()->service.inspect(f.actor(),"inspect",created.resourceNo(),0,"PARTIAL",1,"inspection-proof"));
        assertEquals(1,results.stream().distinct().count());assertEquals(before+1,balance(f).available());
        assertEquals("INSPECTED",service.readReturn(f.actor().tenantId(),created.resourceNo()).status());
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",f.actor().tenantId());assertEquals(results.getFirst(),service.inspect(f.actor(),"after-cache",created.resourceNo(),0,"PARTIAL",1,"inspection-proof"));
        assertCode("FULFILLMENT_CONFLICT",()->service.inspect(f.actor(),"changed",created.resourceNo(),0,"ACCEPTED",2,"inspection-proof"));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",f.actor().tenantId()));
    }
    @Test void fulfillmentIngressAuditFailureRollsBackRmaAndReceipt() {
        Fixture f=paymentFixture(1);var order=paidRefundOrder(f,"ingress-audit",1);var service=application.getBean(com.lrj.trade.order.FulfillmentIngressService.class);
        service.deliver(f.actor(),"deliver",order.orderNo(),"delivery","proof");
        var target=org.springframework.test.util.AopTestUtils.<AuditWriter>getUltimateTargetObject(auditWriter);
        doThrow(new IllegalStateException("audit failed")).when(target).append(any(),eq("fulfillment.ingress.RETURN_CREATE"),anyString(),anyString(),anyString(),anyLong(),anyLong());
        try {assertThrows(IllegalStateException.class,()->service.createReturn(f.actor(),"rma",Long.parseLong(order.items().getFirst().itemId()),f.warehouse(),1));}finally{reset(target);}
        assertEquals(0,count("SELECT COUNT(*) FROM tc_return_request WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_fulfillment_ingress WHERE tenant_id=? AND operation='RETURN_CREATE'",f.actor().tenantId()));
    }
    @Test void fulfillmentIngressHttpSeparatesScopesAndTrustedSources() throws Exception {
        Fixture f=paymentFixture(1);var order=paidRefundOrder(f,"ingress-http",1);String path="/api/v1/trade/admin/orders/"+order.orderNo()+"/fulfillments";
        String body="{\"sourceRequestId\":\"http-delivery\",\"deliveryRef\":\"delivery-proof\"}";
        jwt("fulfillment-admin","trade.admin",f.actor().tenantId());assertEquals(403,request("POST",path,"fulfillment-admin",body,"delivery").statusCode());
        jwt("untrusted","trade.admin trade.fulfillment.write",f.actor().tenantId());assertEquals(403,request("POST",path,"untrusted",body,"delivery").statusCode());
        jwt("fulfillment-admin","trade.admin trade.fulfillment.write",f.actor().tenantId());var response=request("POST",path,"fulfillment-admin",body,"delivery");assertEquals(201,response.statusCode(),response.body());
        jwt("tester","",f.actor().tenantId());var complete=request("POST","/api/v1/trade/orders/"+order.orderNo()+":confirm-receipt","tester",null,"confirm");assertEquals(200,complete.statusCode(),complete.body());
        jwt("fulfillment-service","trade.fulfillment.complete",f.actor().tenantId());
        assertEquals(200,request("POST","/internal/v1/orders/"+order.orderId()+":complete","fulfillment-service","{\"sourceRequestId\":\"service-complete\",\"deliveryEvidenceRef\":\"proof\"}","complete").statusCode());
    }

    @Test void automaticAdmissionBlocksNewWorkButPreservesReplayCallbackRefundAndManualPause() {
        Fixture f=paymentFixture(3);long tenant=f.actor().tenantId();
        var order=factOrder(f,"auto-existing");var unpaid=factOrder(f,"auto-unpaid");
        var payment=payments.create(f.actor(),"auto-pay",order.orderNo(),"TEST_A","return");
        doReturn(true).when(automaticGate).blocked();
        try {
            assertCode("ORDER_ADMISSION_PAUSED",()->factOrder(f,"auto-new"));
            assertCode("PAYMENT_ADMISSION_PAUSED",()->payments.create(f.actor(),"auto-new",unpaid.orderNo(),"TEST_A","return"));
            assertEquals(order.orderNo(),factOrder(f,"auto-existing").orderNo());
            assertEquals(payment.paymentNo(),payments.create(f.actor(),"auto-pay",order.orderNo(),"TEST_A","return").paymentNo());
            assertEquals(payment.paymentNo(),payments.create(f.actor(),"auto-recover",order.orderNo(),"TEST_A","return").paymentNo());
            callbackInbox.accept(tenant,verifiedCallback(f,payment,"auto-event","auto-transaction",100));
            assertEquals(1,callbackWorker.runBatch(tenant,"auto-worker",10).size());
            assertNotNull(requestRefund(f,orders.read(f.actor(),order.orderNo()),"auto-refund",10));
            var service=application.getBean(AdmissionService.class);
            service.update(f.actor(),"auto-manual",0,false,true,"人工暂停继续生效");
            doReturn(false).when(automaticGate).blocked();
            assertCode("ORDER_ADMISSION_PAUSED",()->factOrder(f,"auto-still-paused"));
            assertEquals(1,service.read(tenant).version());
            assertEquals(2,count("SELECT COUNT(*) FROM tc_order WHERE tenant_id=?",tenant));
        } finally {doCallRealMethod().when(automaticGate).blocked();}
    }
    @Test void admissionPauseDoesNotBlockCallbackReadOrRefund() {
        Fixture f=paymentFixture(2);var order=factOrder(f,"admission-callback");var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        application.getBean(AdmissionService.class).update(f.actor(),"pause",0,false,false,"紧急暂停");
        callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,payment,"admission-event","admission-txn",100));
        assertEquals(1,callbackWorker.runBatch(f.actor().tenantId(),"admission-worker",10).size());
        assertEquals("PAID",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(payment.paymentNo(),payments.read(f.actor(),payment.paymentNo()).paymentNo());
        assertNotNull(requestRefund(f,orders.read(f.actor(),order.orderNo()),"admission-refund",10));
    }

    @Test void admissionFirstPauseWaitsForAlreadyAcceptedTransaction() throws Exception {
        Fixture f=paymentFixture(3);var service=application.getBean(AdmissionService.class);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var updaterStarted=new CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var accepted=pool.submit(()->new TransactionTemplate(transactions).execute(status->{
                service.acquire(f.actor().tenantId());locked.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("未释放受理事务");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
                return factOrder(f,"admission-inflight");
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var paused=pool.submit(()->{updaterStarted.countDown();return service.update(f.actor(),"pause",0,false,false,"紧急暂停");});
            try {assertTrue(updaterStarted.await(5,TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->paused.get(200,TimeUnit.MILLISECONDS));}
            finally {release.countDown();}
            assertNotNull(accepted.get(5,TimeUnit.SECONDS));assertFalse(paused.get(5,TimeUnit.SECONDS).orderEnabled());
        } finally {release.countDown();}
        assertCode("ORDER_ADMISSION_PAUSED",()->factOrder(f,"admission-after-pause"));
    }
    @Test void admissionAuditFailureRollsBackFirstPolicyAndCommand() {
        Fixture f=paymentFixture(1);var service=application.getBean(AdmissionService.class);
        var target=org.springframework.test.util.AopTestUtils.<AuditWriter>getUltimateTargetObject(auditWriter);
        doThrow(new IllegalStateException("audit failed")).when(target).append(any(),eq("admission.update"),anyString(),anyString(),anyString(),anyLong(),anyLong());
        try {assertThrows(IllegalStateException.class,()->service.update(f.actor(),"pause",0,false,false,"紧急暂停"));}finally {reset(target);}
        assertEquals(0,count("SELECT COUNT(*) FROM tc_admission_policy WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_command WHERE tenant_id=? AND operation='admission.update'",f.actor().tenantId()));
        assertTrue(service.read(f.actor().tenantId()).orderEnabled());
    }
    @Test void admissionHttpRequiresBothScopesAndStrongVersion() throws Exception {
        Fixture f=paymentFixture(1);long tenant=f.actor().tenantId();String body="{\"orderEnabled\":false,\"paymentEnabled\":false,\"reason\":\"暂停\"}";
        jwt("operator","trade.admin",tenant);assertEquals(403,admissionPatch(body,"\"0\"","pause").statusCode());
        jwt("operator","trade.admission.write",tenant);assertEquals(403,admissionPatch(body,"\"0\"","pause").statusCode());
        jwt("operator","trade.admin trade.admission.write",tenant);
        var read=request("GET","/api/v1/trade/admin/admission-policy","operator",null);assertEquals("\"0\"",read.headers().firstValue("ETag").orElseThrow());
        assertEquals(428,admissionPatch(body,null,"pause").statusCode());assertEquals(400,admissionPatch(body,"W/\"0\"","pause").statusCode());
        var first=admissionPatch(body,"\"0\"","pause");assertEquals(200,first.statusCode(),first.body());assertEquals("\"1\"",first.headers().firstValue("ETag").orElseThrow());
        assertEquals(first.body(),admissionPatch(body,"\"0\"","pause").body());assertEquals(412,admissionPatch(body,"\"0\"","other").statusCode());
        jwt("operator","trade.admin trade.admission.write",tenant+100000);
        assertEquals("\"0\"",request("GET","/api/v1/trade/admin/admission-policy","operator",null).headers().firstValue("ETag").orElseThrow());
    }
    private HttpResponse<String> admissionPatch(String body,String version,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/trade/admin/admission-policy"))
            .header("Authorization","Bearer operator").header("Content-Type","application/json").header("Idempotency-Key",key);
        if(version!=null) builder.header("If-Match",version);
        return HttpClient.newHttpClient().send(builder.method("PATCH",HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void admissionPausePreservesReplayAndRejectsOnlyNewAcceptance() {
        Fixture f=paymentFixture(5);long tenant=f.actor().tenantId();var service=application.getBean(AdmissionService.class);
        var order=factOrder(f,"admission-existing");var unpaid=factOrder(f,"admission-unpaid");
        var payment=payments.create(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var closed=service.update(f.actor(),"pause",0,false,false,"紧急暂停");assertEquals(1,closed.version());
        assertEquals(order.orderNo(),factOrder(f,"admission-existing").orderNo());
        assertEquals(payment.paymentNo(),payments.create(f.actor(),"pay",order.orderNo(),"TEST_A","return").paymentNo());
        assertCode("ORDER_ADMISSION_PAUSED",()->factOrder(f,"admission-new"));
        assertCode("PAYMENT_ADMISSION_PAUSED",()->payments.create(f.actor(),"new",unpaid.orderNo(),"TEST_A","return"));
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=? AND operation='payment.create'",tenant);
        assertEquals(payment.paymentNo(),payments.create(f.actor(),"repeat",order.orderNo(),"TEST_A","return").paymentNo());
        assertCode("PAYMENT_UNRESOLVED",()->payments.create(f.actor(),"other",order.orderNo(),"TEST_B","return"));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_intent WHERE tenant_id=?",tenant));
        service.update(f.actor(),"resume",1,true,true,"恢复受理");assertEquals(closed,service.update(f.actor(),"pause",0,false,false,"紧急暂停"));
        assertTrue(service.read(tenant).orderEnabled());assertNotNull(factOrder(f,"admission-new"));
        assertCode("PRECONDITION_FAILED",()->service.update(f.actor(),"old",0,false,false,"过期策略"));
    }
    @Test void admissionRejectsOuterReadCommittedBeforeDomainWrites() {
        Fixture f=paymentFixture(1);var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->factOrder(f,"admission-rc")));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_order WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_admission_policy WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void reconciliationOperationConcurrentPermanentReceiptAndVersionRace() throws Exception {
        Fixture f=paymentFixture(1);String caseNo=queryCase(f,"request");long tenant=f.actor().tenantId();var service=application.getBean(ReconciliationOperationService.class);
        var results=parallel(20,()->service.request(f.actor(),"same",caseNo,0,"QUERY_PAYMENT","核对原渠道","ticket-1"));assertEquals(1,new HashSet<>(results).size());
        var first=results.getFirst();assertEquals("QUEUED",first.initialStatus());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_reconciliation_operation WHERE tenant_id=?",tenant));assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='RECON_QUERY'",tenant));
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",tenant);assertEquals(first,service.request(f.actor(),"same",caseNo,0,"QUERY_PAYMENT","核对原渠道","ticket-1"));
        assertCode("IDEMPOTENCY_CONFLICT",()->service.request(f.actor(),"same",caseNo,0,"QUERY_PAYMENT","改变原因","ticket-1"));
        var sequence=new AtomicLong();var losers=parallel(10,()->{try {service.request(f.actor(),"other-"+sequence.incrementAndGet(),caseNo,0,"QUERY_PAYMENT","核对","ticket");return false;}catch(TradeException error){assertEquals("VERSION_CONFLICT",error.code());return true;}});assertTrue(losers.stream().allMatch(Boolean::booleanValue));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_reconciliation_operation WHERE tenant_id=?",tenant));
    }
    @Test void reconciliationOperationAuditFailureRollsBackCaseAndJob() {
        Fixture f=paymentFixture(1);String caseNo=queryCase(f,"rollback");long tenant=f.actor().tenantId();var service=application.getBean(ReconciliationOperationService.class);
        var target=org.springframework.test.util.AopTestUtils.<AuditWriter>getUltimateTargetObject(auditWriter);
        doThrow(new IllegalStateException("audit failed")).when(target).append(any(),eq("reconciliation.query"),anyString(),anyString(),anyString(),anyLong(),anyLong());
        try {assertThrows(IllegalStateException.class,()->service.request(f.actor(),"rollback",caseNo,0,"QUERY_PAYMENT","核对","ticket"));}finally{reset(target);}
        assertEquals(0,count("SELECT COUNT(*) FROM tc_reconciliation_operation WHERE tenant_id=?",tenant));assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='RECON_QUERY'",tenant));
        assertEquals("OPEN",application.getBean(ReconciliationCaseService.class).read(tenant,caseNo).status());
    }
    @Test void reconciliationOperationAbsenceReviewsWithoutRecreateAndPermitsNewRound() {
        Fixture f=paymentFixture(1);String caseNo=queryCase(f,"absence");long tenant=f.actor().tenantId();var service=application.getBean(ReconciliationOperationService.class);
        var first=service.request(f.actor(),"one",caseNo,0,"QUERY_PAYMENT","核对","ticket");
        var executor=mock(ReconciliationQueryExecutorPort.class);when(executor.execute(anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(ReconciliationQueryExecutorPort.Outcome.REPLAYABLE_ABSENCE);
        var worker=new com.lrj.trade.jobs.ReconciliationQueryWorker(jobLeases,service,executor,transactions,true);
        assertEquals(List.of(com.lrj.trade.jobs.ReconciliationQueryWorker.Outcome.REVIEW),worker.runBatch(tenant,"query-worker",1));
        assertEquals(1,service.page(tenant,"REVIEW",0,100).size());assertEquals("PROCESSING",application.getBean(ReconciliationCaseService.class).read(tenant,caseNo).status());
        assertEquals(first,service.request(f.actor(),"one",caseNo,0,"QUERY_PAYMENT","核对","ticket"));
        var second=service.request(f.actor(),"two",caseNo,1,"QUERY_PAYMENT","再次核对","ticket");assertNotEquals(first.operationNo(),second.operationNo());assertEquals(2,second.caseVersion());
        verify(executor,times(1)).execute(anyLong(),anyLong(),eq("QUERY_PAYMENT"),eq("RECEIPT"),anyString());assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",tenant));
    }
    @Test void reconciliationOperationStaleLeaseCannotCommitSyntheticAcceptedOutcome() {
        Fixture f=paymentFixture(1);String caseNo=queryCase(f,"fencing");long tenant=f.actor().tenantId();var service=application.getBean(ReconciliationOperationService.class);
        var operation=service.request(f.actor(),"one",caseNo,0,"QUERY_PAYMENT","核对","ticket");var executor=mock(ReconciliationQueryExecutorPort.class);
        var takeover=new java.util.concurrent.atomic.AtomicReference<com.lrj.trade.jobs.model.JobLease>();
        when(executor.execute(anyLong(),anyLong(),anyString(),anyString(),anyString())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            try {
                jdbc.update("UPDATE tc_job_lease SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)),next_run_at=lease_until WHERE tenant_id=? AND job_type='RECON_QUERY'",tenant);
                takeover.set(jobLeases.claim(tenant,JobType.RECON_QUERY,"new-owner",1,30000).getFirst());
            } catch(RuntimeException fixtureFailure) {throw new AssertionError("接管夹具失败，不能冒充渠道不确定结果",fixtureFailure);}
            return ReconciliationQueryExecutorPort.Outcome.EVIDENCE_ACCEPTED;
        });
        var worker=new com.lrj.trade.jobs.ReconciliationQueryWorker(jobLeases,service,executor,transactions,true);
        assertEquals(List.of(com.lrj.trade.jobs.ReconciliationQueryWorker.Outcome.LEASE_LOST),worker.runBatch(tenant,"old-owner",1));
        assertEquals(0,service.page(tenant,"RESULT_ACCEPTED",0,100).size());assertEquals(1,service.page(tenant,"RUNNING",0,100).size());
        reset(executor);when(executor.execute(anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(ReconciliationQueryExecutorPort.Outcome.EVIDENCE_ACCEPTED);
        assertEquals(com.lrj.trade.jobs.ReconciliationQueryWorker.Outcome.RESULT_ACCEPTED,worker.processLease(takeover.get()));
        assertEquals(1,service.page(tenant,"RESULT_ACCEPTED",0,100).size());assertEquals("PROCESSING",application.getBean(ReconciliationCaseService.class).read(tenant,caseNo).status());
        // 执行器结果在此人工替身下只证明fencing编排，不冒充真实渠道证据。
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='reconciliation.query.result' AND resource_type='RECONCILIATION_OPERATION' AND resource_id=?",tenant,operation.operationNo()));
    }
    @Test void reconciliationOperationRetryBudgetAndMissingTaskAreBounded() {
        Fixture f=paymentFixture(1);String caseNo=queryCase(f,"budget");long tenant=f.actor().tenantId();var service=application.getBean(ReconciliationOperationService.class);
        service.request(f.actor(),"one",caseNo,0,"QUERY_PAYMENT","核对","ticket");var executor=mock(ReconciliationQueryExecutorPort.class);
        when(executor.execute(anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(ReconciliationQueryExecutorPort.Outcome.UNKNOWN);
        var worker=new com.lrj.trade.jobs.ReconciliationQueryWorker(jobLeases,service,executor,transactions,true);
        for(int i=0;i<5;i++) {assertEquals(List.of(i==4?com.lrj.trade.jobs.ReconciliationQueryWorker.Outcome.REVIEW:com.lrj.trade.jobs.ReconciliationQueryWorker.Outcome.RETRY),worker.runBatch(tenant,"retry",1));jdbc.update("UPDATE tc_job_lease SET next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND job_type='RECON_QUERY'",tenant);}
        assertEquals(1,service.page(tenant,"REVIEW",0,100).size());
        new TransactionTemplate(transactions).executeWithoutResult(tx->jobLeases.enqueue(tenant,JobType.RECON_QUERY,UUID.randomUUID().toString()));
        assertEquals(List.of(com.lrj.trade.jobs.ReconciliationQueryWorker.Outcome.REVIEW),worker.runBatch(tenant,"missing",1));
        verify(executor,times(5)).execute(anyLong(),anyLong(),anyString(),anyString(),anyString());
    }
    @Test void reconciliationOperationHttpNeedsBothScopesAndTenantMatch() throws Exception {
        Fixture f=paymentFixture(1);String caseNo=queryCase(f,"http-operation");long tenant=f.actor().tenantId();String path="/api/v1/trade/admin/reconciliation-cases/"+caseNo+":query";
        String body="{\"expectedVersion\":0,\"action\":\"QUERY_PAYMENT\",\"reason\":\"核对\",\"ticketRef\":\"ticket\"}";
        jwt("operator","trade.admin",tenant);assertEquals(403,request("POST",path,"operator",body,"one").statusCode());
        jwt("operator","trade.reconciliation.query",tenant);assertEquals(403,request("POST",path,"operator",body,"one").statusCode());
        jwt("operator","trade.admin trade.reconciliation.query",tenant);
        assertEquals(428,versionRequest("POST",path,"operator",body,"missing-condition",null).statusCode());
        var first=request("POST",path,"operator",body,"one");assertEquals(202,first.statusCode(),first.body());assertEquals(first.body(),request("POST",path,"operator",body,"one").body());assertEquals(412,request("POST",path,"operator",body,"stale-operation").statusCode());
        jwt("operator","trade.admin trade.reconciliation.query",tenant+10000);assertEquals(404,request("POST",path,"operator",body,"one").statusCode());
        assertEquals("[]",request("GET","/api/v1/trade/admin/reconciliation-operations?state=QUEUED","operator",null).body());
    }
    private String queryCase(Fixture f,String key) {
        var receipt=refundReceipt(f,"query-case-"+key,1);
        return application.getBean(ReconciliationCaseService.class).accept(f.actor(),"recon-platform",caseReport(f,"query-report-"+key,0,null,"RECEIPT",receipt.receiptNo())).caseNo();
    }

    @Test void reconciliationQueryPlansKeepOriginalChannelAndNeverDispatchUnsubmittedRefund() {
        Fixture f=paymentFixture(1);var order=paidRefundOrder(f,"query-only",1);long tenant=f.actor().tenantId(),channel=reconciliationChannel(f,"TEST_A");
        var paymentPlans=application.getBean(com.lrj.trade.payment.PaymentQueryPlanPort.class);
        String receipt=jdbc.queryForObject("SELECT receipt_no FROM tc_payment_receipt WHERE tenant_id=?",String.class,tenant);
        var plan=paymentPlans.query(tenant,channel,"RECEIPT",receipt);assertEquals("QUERY",plan.action());
        assertCode("RECONCILIATION_BINDING_CONFLICT",()->paymentPlans.query(tenant,reconciliationChannel(f,"TEST_B"),"RECEIPT",receipt));
        assertCode("RECONCILIATION_BINDING_CONFLICT",()->paymentPlans.query(tenant+10000,channel,"RECEIPT",receipt));
        var refund=requestRefund(f,order,"query-refund",10);var refundPlans=application.getBean(com.lrj.trade.refund.RefundQueryPlanPort.class);
        assertCode("RECONCILIATION_QUERY_UNAVAILABLE",()->refundPlans.query(tenant,channel,refund.refundNo()));
        approveRefund(f,refund,true,1);var dispatched=prepareRefund(f,refund);
        var before=refunds.read(f.actor(),refund.refundNo());var facts=refundPlans.query(tenant,channel,refund.refundNo());
        assertEquals("QUERY",facts.action());assertEquals(dispatched.merchantRefundNo(),facts.merchantRefundNo());assertEquals(dispatched.providerTransactionNo(),facts.providerTransactionNo());
        assertEquals(before,refunds.read(f.actor(),refund.refundNo()));assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",tenant));
        assertCode("RECONCILIATION_BINDING_CONFLICT",()->refundPlans.query(tenant,reconciliationChannel(f,"TEST_B"),refund.refundNo()));
        assertCode("NOT_FOUND",()->refundPlans.query(tenant+10000,channel,refund.refundNo()));
    }

    @Test void reconciliationCasePermanentEventAndDifferentEventsShareOneCase() throws Exception {
        Fixture f=paymentFixture(1);long tenant=f.actor().tenantId();var service=application.getBean(ReconciliationCaseService.class);
        var report=caseReport(f,"event",0,null,null,null);
        var results=parallel(20,()->service.accept(f.actor(),"recon-platform",report));
        assertEquals(1,new HashSet<>(results).size());var first=results.getFirst();
        var byNumber=service.read(tenant,first.caseNo());
        assertTrue(byNumber.businessNo().matches("TRD-REC-[0-9]{6}-[0-9]{10}"));
        assertEquals(byNumber,service.read(tenant,byNumber.businessNo()));
        assertEquals(1,service.page(tenant,"OPEN",0,20,byNumber.businessNo()).size());
        assertTrue(service.page(tenant+10000,"OPEN",0,20,byNumber.businessNo()).isEmpty());
        var sequence=new AtomicLong();
        var newEvents=parallel(12,()->service.accept(f.actor(),"recon-platform",caseReport(f,"next-"+sequence.incrementAndGet(),0,null,null,null)));
        assertTrue(newEvents.stream().allMatch(r->r.caseNo().equals(first.caseNo())));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_reconciliation_case WHERE tenant_id=?",tenant));
        assertEquals(13,count("SELECT COUNT(*) FROM tc_reconciliation_report_receipt WHERE tenant_id=?",tenant));
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",tenant);
        assertEquals(first,service.accept(f.actor(),"recon-platform",report));
        assertCode("IDEMPOTENCY_CONFLICT",()->service.accept(f.actor(),"recon-platform",caseReport(f,"event",1,null,null,null)));
    }
    @Test void reconciliationCaseConflictingEvidenceAndCrossBindingRollbackReceipts() {
        Fixture f=paymentFixture(1);var receipt=refundReceipt(f,"case",1);long tenant=f.actor().tenantId();
        var service=application.getBean(ReconciliationCaseService.class);var original=caseReport(f,"first",0,null,"RECEIPT",receipt.receiptNo());
        service.accept(f.actor(),"recon-platform",original);
        var invalidReference=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE tc_reconciliation_case SET reference_type=NULL WHERE tenant_id=?",tenant));
        assertEquals(3819,((java.sql.SQLException)invalidReference.getMostSpecificCause()).getErrorCode());
        assertCode("RECONCILIATION_CASE_CONFLICT",()->service.accept(f.actor(),"recon-platform",caseReport(f,"conflict",0,null,null,null)));
        var wrong=new ReconciliationCaseService.Report("wrong",reconciliationChannel(f,"TEST_B"),original.statementDate(),"other",0,"AMOUNT","RECEIPT",receipt.receiptNo(),original.evidenceHash(),original.evidenceRef(),null);
        assertCode("RECONCILIATION_BINDING_CONFLICT",()->service.accept(f.actor(),"recon-platform",wrong));
        assertCode("NOT_FOUND",()->service.accept(new Actor(tenant+10000,"https://identity.test","recon"),"recon-platform",original));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_reconciliation_report_receipt WHERE tenant_id=?",tenant));
    }
    @Test void reconciliationCaseRevisionChainAndAuditRollbackAreAtomic() {
        Fixture f=paymentFixture(1);long tenant=f.actor().tenantId();var service=application.getBean(ReconciliationCaseService.class);
        var first=service.accept(f.actor(),"recon-platform",caseReport(f,"first",0,null,null,null));
        var second=service.accept(f.actor(),"recon-platform",caseReport(f,"second",1,first.caseNo(),null,null));assertNotEquals(first.caseNo(),second.caseNo());
        assertCode("RECONCILIATION_CASE_CONFLICT",()->service.accept(f.actor(),"recon-platform",caseReport(f,"bad-prior",1,second.caseNo(),null,null)));
        var auditTarget=org.springframework.test.util.AopTestUtils.<AuditWriter>getUltimateTargetObject(auditWriter);
        doThrow(new IllegalStateException("audit unavailable")).when(auditTarget).append(any(),eq("reconciliation.report"),anyString(),anyString(),anyString(),anyLong(),anyLong());
        try {assertThrows(IllegalStateException.class,()->service.accept(f.actor(),"recon-platform",caseReport(f,"rollback",2,second.caseNo(),null,null)));}
        finally {reset(auditTarget);}
        assertEquals(2,count("SELECT COUNT(*) FROM tc_reconciliation_case WHERE tenant_id=?",tenant));
        assertEquals(2,count("SELECT COUNT(*) FROM tc_reconciliation_report_receipt WHERE tenant_id=?",tenant));
        assertEquals(2,service.page(tenant,"OPEN",0,100).size());assertTrue(service.page(tenant+10000,"OPEN",0,100).isEmpty());
    }
    @Test void reconciliationCaseOwnershipReadsAttemptReceiptAndRefundWithoutDispatch() {
        Fixture f=paymentFixture(1);var receipt=refundReceipt(f,"ownership",1);reserveRefund(f,receipt,"held",10);
        long tenant=f.actor().tenantId(),channel=reconciliationChannel(f,"TEST_A");var port=application.getBean(ReconciliationOwnershipPort.class);
        String attempt=jdbc.queryForObject("SELECT merchant_request_no FROM tc_payment_attempt WHERE tenant_id=?",String.class,tenant);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{port.require(tenant,channel,"PAYMENT_ATTEMPT",attempt);port.require(tenant,channel,"RECEIPT",receipt.receiptNo());port.require(tenant,channel,"REFUND","held");});
        assertEquals("HELD",jdbc.queryForObject("SELECT state FROM tc_payment_refund_hold WHERE tenant_id=? AND refund_no='held'",String.class,tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",tenant));
    }
    @Test void reconciliationCaseHttpSeparatesReportAndAdminPermissions() throws Exception {
        Fixture f=paymentFixture(1);long tenant=f.actor().tenantId();var r=caseReport(f,"http",0,null,null,null);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();String path="/internal/v1/reconciliation/reports";
        String body=json.writeValueAsString(Map.of("eventId",r.eventId(),"channelId",Long.toString(r.channelId()),"statementDate",r.statementDate().toString(),"externalLineKey",r.externalLineKey(),"revision",r.revision(),"differenceType",r.differenceType(),"evidenceHash",r.evidenceHash(),"evidenceRef",r.evidenceRef()));
        jwt("recon-reporter","trade.reconciliation.read",tenant);assertEquals(403,request("POST",path,"recon-reporter",body).statusCode());
        jwt("recon-reader","trade.reconciliation.report",tenant);assertEquals(403,request("POST",path,"recon-reader",body).statusCode());
        jwt("recon-reporter","trade.reconciliation.report",tenant);var accepted=request("POST",path,"recon-reporter",body);assertEquals(201,accepted.statusCode(),accepted.body());
        assertEquals(accepted.body(),request("POST",path,"recon-reporter",body).body());
        String detail="/internal/v1/reconciliation/cases/"+json.readTree(accepted.body()).path("caseNo").asText();
        assertEquals(403,request("GET",detail,"recon-reporter",null).statusCode());
        jwt("recon-reader","trade.reconciliation.read",tenant);var read=request("GET",detail,"recon-reader",null);assertEquals(200,read.statusCode(),read.body());assertTrue(read.body().contains("evidenceHash"));
        jwt("recon-reader","trade.reconciliation.read",tenant+10000);assertEquals(404,request("GET",detail,"recon-reader",null).statusCode());
        assertEquals(403,request("GET","/api/v1/trade/admin/reconciliation-cases","recon-reporter",null).statusCode());
        jwt("case-admin","trade.admin",tenant);var queue=request("GET","/api/v1/trade/admin/reconciliation-cases","case-admin",null);assertEquals(200,queue.statusCode());assertFalse(queue.body().contains("evidenceRef"));
        jwt("case-admin","trade.admin",tenant+10000);assertEquals("[]",request("GET","/api/v1/trade/admin/reconciliation-cases","case-admin",null).body());
    }
    private ReconciliationCaseService.Report caseReport(Fixture f,String event,long revision,String prior,String type,String ref) {
        return new ReconciliationCaseService.Report(event,reconciliationChannel(f,"TEST_A"),java.time.LocalDate.of(2026,9,7),"line-1",revision,"AMOUNT",type,ref,Checks.hash("immutable-statement"),"statement:sha256:"+Checks.hash("immutable-statement"),prior);
    }

    @Test void reconciliationFactsIncludeAbnormalAndOnlySucceededRefunds() {
        Fixture f=paymentFixture(1);var order=factOrder(f,"recon");var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var normal=receive(f,payment,"normal",100,"CNY");var extra=receive(f,payment,"extra",150,"CNY");
        reserveRefund(f,normal,"pending",10);reserveRefund(f,extra,"part1",40);
        var refundAt=clock.instant().plusSeconds(20);confirmRefund(f,"part1","refund-part1",40,refundAt);
        reserveRefund(f,extra,"part2",30);confirmRefund(f,"part2","refund-part2",30,refundAt.plusSeconds(1));
        long tenant=f.actor().tenantId(),channel=reconciliationChannel(f,"TEST_A");
        var result=application.getBean(ReconciliationFactsPort.class).scan(tenant,channel,0,null,100);
        assertEquals(4,result.facts().size());assertFalse(result.hasMore());assertEquals(4,result.scannedCount());
        assertEquals(Set.of("NORMAL","EXCESS"),result.facts().stream().map(ReconciliationFactsPort.Fact::disposition).collect(java.util.stream.Collectors.toSet()));
        var refunds=result.facts().stream().filter(row->row.direction().equals("REFUND")).toList();
        assertEquals(70,refunds.stream().mapToLong(ReconciliationFactsPort.Fact::amount).sum());
        assertEquals(refundAt,refunds.getFirst().providerOccurredAt());assertEquals(clock.instant(),refunds.getFirst().confirmedAt());
        assertTrue(result.facts().stream().noneMatch(row->"pending".equals(row.refundNo())));
        jdbc.update("UPDATE tc_merchant_channel SET status='FROZEN' WHERE tenant_id=? AND id=?",tenant,channel);
        assertEquals(result.facts(),application.getBean(ReconciliationFactsPort.class).scan(tenant,channel,0,null,100).facts());
    }
    @Test void reconciliationFactsEmptyChannelPagesAdvanceBoundedCandidateCursor() {
        Fixture f=paymentFixture(3);refundReceipt(f,"one",1);refundReceipt(f,"two",1);
        long tenant=f.actor().tenantId(),channel=reconciliationChannel(f,"TEST_B");var service=application.getBean(ReconciliationFactsPort.class);
        var first=service.scan(tenant,channel,0,null,1);assertTrue(first.facts().isEmpty());assertTrue(first.hasMore());assertEquals(1,first.scannedCount());
        refundReceipt(f,"later",1);
        var second=service.scan(tenant,channel,Long.parseLong(first.nextCursor()),Long.parseLong(first.upper()),1);
        assertTrue(second.facts().isEmpty());assertFalse(second.hasMore());assertEquals(1,second.scannedCount());
        assertEquals(3,service.scan(tenant,reconciliationChannel(f,"TEST_A"),0,null,100).facts().size());
        assertCode("NOT_FOUND",()->service.scan(tenant+10000,channel,0,null,100));
        assertCode("INVALID_ARGUMENT",()->service.scan(tenant,channel,2,1L,100));
        assertCode("INVALID_ARGUMENT",()->service.scan(tenant,channel,0,null,101));
        var sql=sqlSessions.getConfiguration().getMappedStatement("com.lrj.trade.payment.mapper.ReconciliationFactsMapper.candidates").getBoundSql(Map.of("tenant",tenant,"after",0,"upper",Long.MAX_VALUE,"limit",101)).getSql();
        var plan=jdbc.queryForList("EXPLAIN "+sql,tenant,0,Long.MAX_VALUE,101);
        assertTrue(plan.stream().allMatch(row->"ix_money_ledger_scan".equals(row.get("key"))));
    }
    @Test void reconciliationFactsNewRoundFindsLateCommittedLowerLedgerId() throws Exception {
        Fixture f=paymentFixture(2);var one=refundReceipt(f,"late-one",1);var two=refundReceipt(f,"late-two",1);
        reserveRefund(f,one,"late-refund",10);reserveRefund(f,two,"early-refund",10);
        long tenant=f.actor().tenantId(),channel=reconciliationChannel(f,"TEST_A");
        var service=application.getBean(ReconciliationFactsPort.class);var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var writer=pool.submit(()->new TransactionTemplate(transactions).execute(tx->{
                confirmRefund(f,"late-refund","provider-late",10,clock.instant());inserted.countDown();
                try {assertTrue(release.await(10,TimeUnit.SECONDS));} catch(InterruptedException e) {throw new IllegalStateException(e);}return true;
            }));
            try {
                assertTrue(inserted.await(5,TimeUnit.SECONDS));confirmRefund(f,"early-refund","provider-early",10,clock.instant());
                var old=service.scan(tenant,channel,0,null,100);assertEquals(3,old.facts().size());
                release.countDown();assertTrue(writer.get(5,TimeUnit.SECONDS));
                assertTrue(service.scan(tenant,channel,Long.parseLong(old.nextCursor()),Long.parseLong(old.upper()),100).facts().isEmpty());
                assertEquals(4,service.scan(tenant,channel,0,null,100).facts().size());
            } finally {release.countDown();}
        }
    }
    @Test void reconciliationFactsHttpRequiresFinanceScopeAndSeparateServiceIdentity() throws Exception {
        Fixture f=paymentFixture(1);refundReceipt(f,"http",1);long tenant=f.actor().tenantId();
        String path="/internal/v1/reconciliation/money-facts?channelId="+reconciliationChannel(f,"TEST_A");
        assertEquals(401,request("GET",path,null,null).statusCode());
        jwt("recon-reader","trade.admin",tenant);assertEquals(403,request("GET",path,"recon-reader",null).statusCode());
        jwt("evidence-reader","trade.reconciliation.read",tenant);assertEquals(403,request("GET",path,"evidence-reader",null).statusCode());
        jwt("recon-reader","trade.reconciliation.read",tenant);var response=request("GET",path,"recon-reader",null);
        assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains("BOUNDED_SCAN"));
        assertFalse(response.body().contains("vault:"));assertFalse(response.body().contains("evidenceRef"));
        assertEquals(400,request("GET",path+"&scanLimit=101","recon-reader",null).statusCode());
        jwt("recon-reader","trade.reconciliation.read",tenant+10000);assertEquals(404,request("GET",path,"recon-reader",null).statusCode());
    }
    private long reconciliationChannel(Fixture f,String code) {
        return jdbc.queryForObject("SELECT id FROM tc_merchant_channel WHERE tenant_id=? AND channel_code=?",Long.class,f.actor().tenantId(),code);
    }

    @Test void outboxMaintenanceFixedUpperSkipsV0AndDoesNotReviveReviewedJobs() {
        long tenant=TENANTS.incrementAndGet();var first=legacyPublication(tenant);
        new TransactionTemplate(transactions).executeWithoutResult(t->application.getBean(Outbox.class).append(tenant,"member.internal.v0","1"));
        legacyPublication(tenant);var service=application.getBean(OutboxMaintenancePort.class);
        assertEquals(new OutboxMaintenancePort.Step(true,1,1,false),service.scanPage(tenant,1,60000));
        long upper=count("SELECT round_upper_id FROM tc_outbox_maintenance WHERE tenant_id=?",tenant);
        var later=legacyPublication(tenant);OutboxMaintenancePort.Step step;
        do { step=service.scanPage(tenant,1,60000); } while(!step.completed());
        assertEquals(2,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='OUTBOX_PUBLISH'",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND business_key=?",tenant,later.eventId()));
        assertEquals(upper,count("SELECT last_upper_id FROM tc_outbox_maintenance WHERE tenant_id=?",tenant));
        var lease=jobLeases.claim(tenant,JobType.OUTBOX_PUBLISH,"review",1,30000).getFirst();
        new TransactionTemplate(transactions).executeWithoutResult(t->jobLeases.review(lease,"TEST_REVIEW"));
        assertFalse(service.scanPage(tenant,100,60000).worked());
        jdbc.update("UPDATE tc_outbox_maintenance SET next_round_at=UTC_TIMESTAMP(3) WHERE tenant_id=?",tenant);
        assertTrue(service.scanPage(tenant,100,60000).completed());
        assertEquals(3,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=?",tenant));
        assertEquals("REVIEW",jdbc.queryForObject("SELECT status FROM tc_job_lease WHERE tenant_id=? AND business_key=?",String.class,tenant,first.eventId()));
        assertEquals(2,count("SELECT completed_rounds FROM tc_outbox_maintenance WHERE tenant_id=?",tenant));
    }

    @Test void outboxMaintenanceConcurrentWorkersSharePersistentCursor() throws Exception {
        long tenant=TENANTS.incrementAndGet();for(int i=0;i<12;i++) legacyPublication(tenant);
        var service=application.getBean(OutboxMaintenancePort.class);
        parallel(20,()->service.scanPage(tenant,1,60000));
        assertEquals(12,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=?",tenant));
        assertEquals(12,count("SELECT last_scanned_count FROM tc_outbox_maintenance WHERE tenant_id=?",tenant));
        assertEquals(1,count("SELECT completed_rounds FROM tc_outbox_maintenance WHERE tenant_id=?",tenant));
        assertFalse(service.scanPage(tenant,100,60000).worked());
    }

    @Test void outboxMaintenanceEnqueueFailureDoesNotAdvanceOrPartiallyCommitPage() {
        long tenant=TENANTS.incrementAndGet();for(int i=0;i<3;i++) legacyPublication(tenant);
        var real=application.getBean(OutboxMaintenancePort.class);real.scanPage(tenant,1,60000);
        long cursor=count("SELECT cursor_id FROM tc_outbox_maintenance WHERE tenant_id=?",tenant);
        JobSchedulerPort failed=mock(JobSchedulerPort.class);
        doAnswer(c->{jobLeases.enqueue(c.getArgument(0),c.getArgument(1),c.getArgument(2));throw new org.springframework.dao.DataIntegrityViolationException("模拟入队后页失败");})
            .when(failed).enqueue(anyLong(),any(),anyString());
        var service=new OutboxMaintenanceService(application.getBean(com.lrj.trade.core.mapper.OutboxMaintenanceMapper.class),failed,transactions);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.scanPage(tenant,2,60000));
        assertEquals(cursor,count("SELECT cursor_id FROM tc_outbox_maintenance WHERE tenant_id=?",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=?",tenant));
        assertTrue(real.scanPage(tenant,2,60000).completed());
        assertEquals(3,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=?",tenant));
    }

    @Test void outboxMaintenanceNextRoundFindsLowerIdCommittedAfterFirstRound() throws Exception {
        long tenant=TENANTS.incrementAndGet();var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        var lower=new java.util.concurrent.atomic.AtomicReference<MemberRegisteredEvent>();
        try(var pool=Executors.newSingleThreadExecutor()) {
            var writer=pool.submit(()->new TransactionTemplate(transactions).execute(t->{
                var event=legacyPublication(tenant);lower.set(event);inserted.countDown();
                try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return event;
            }));
            try {
                assertTrue(inserted.await(5,TimeUnit.SECONDS));legacyPublication(tenant);
                var service=application.getBean(OutboxMaintenancePort.class);assertTrue(service.scanPage(tenant,100,60000).completed());
                assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=?",tenant));
                release.countDown();writer.get(5,TimeUnit.SECONDS);
                assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND business_key=?",tenant,lower.get().eventId()));
                jdbc.update("UPDATE tc_outbox_maintenance SET next_round_at=UTC_TIMESTAMP(3) WHERE tenant_id=?",tenant);
                assertTrue(service.scanPage(tenant,100,60000).completed());
                assertEquals(2,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=?",tenant));
            } finally {release.countDown();}
        }
    }

    @Test void outboxMaintenanceDiscoveryAndQueueObservationUseBoundedIndexes() {
        long first=TENANTS.incrementAndGet(),second=TENANTS.incrementAndGet();legacyPublication(first);legacyPublication(second);
        var maintenance=application.getBean(OutboxMaintenancePort.class);
        assertEquals(first,maintenance.nextTenant(first-1).orElseThrow());assertEquals(second,maintenance.nextTenant(first).orElseThrow());
        var plan=jdbc.queryForMap("EXPLAIN SELECT tenant_id FROM tc_outbox WHERE tenant_id>? ORDER BY tenant_id LIMIT 1",first-1);
        assertTrue(String.valueOf(plan.get("Extra")).contains("Using index"));assertFalse(String.valueOf(plan.get("Extra")).contains("filesort"));
        var statement=sqlSessions.getConfiguration().getMappedStatement("com.lrj.trade.jobs.mapper.OutboxQueueSnapshotMapper.snapshot");
        var plans=jdbc.queryForList("EXPLAIN "+statement.getBoundSql(null).getSql());
        var tables=plans.stream().filter(p->"tc_job_lease".equals(p.get("table"))).toList();
        assertEquals(3,tables.size());assertTrue(tables.stream().allMatch(p->"ix_job_publication_age".equals(p.get("key"))));
    }

    @Test void outboxMaintenanceQueueSnapshotAndMetricsEndpointAreProtected() throws Exception {
        var source=application.getBean(OutboxQueueSnapshotPort.class);var before=source.snapshot();long tenant=TENANTS.incrementAndGet();
        new TransactionTemplate(transactions).executeWithoutResult(t->{jobLeases.enqueue(tenant,JobType.OUTBOX_PUBLISH,UUID.randomUUID().toString());jobLeases.enqueue(tenant,JobType.OUTBOX_PUBLISH,UUID.randomUUID().toString());});
        var lease=jobLeases.claim(tenant,JobType.OUTBOX_PUBLISH,"review",1,30000).getFirst();
        new TransactionTemplate(transactions).executeWithoutResult(t->jobLeases.review(lease,"TEST_REVIEW"));
        var after=source.snapshot();
        for(String state:List.of("READY","REVIEW")) {
            long prior=before.states().stream().filter(r->r.status().equals(state)).findFirst().orElseThrow().countLowerBound();
            var current=after.states().stream().filter(r->r.status().equals(state)).findFirst().orElseThrow();
            assertEquals(Math.min(10001,prior+1),current.countLowerBound());assertNotNull(current.oldestAt());
        }
        jwt("metrics-reader","trade.metrics.read",tenant);jwt("metrics-admin","trade.admin",tenant);
        assertEquals(401,request("GET","/actuator/prometheus",null,null).statusCode());
        assertEquals(403,request("GET","/actuator/prometheus","metrics-admin",null).statusCode());
        var response=request("GET","/actuator/prometheus","metrics-reader",null);assertEquals(200,response.statusCode(),response.body());
        assertTrue(response.body().contains("jvm_memory_used_bytes"));
    }

    private MemberRegisteredEvent legacyPublication(long tenant) {
        var event=MemberRegisteredEvent.create(tenant,100,clock.instant());
        return new TransactionTemplate(transactions).execute(t->{
            application.getBean(com.lrj.trade.core.mapper.OutboxMapper.class).insertV1Event(tenant,event.eventId(),event.eventType(),event.aggregateId(),MemberEventCodec.encode(event));return event;
        });
    }

    @Test void outboxReplayKeepsPermanentResultAfterCommandEvictionAndConcurrentRetries() throws Exception {
        Actor actor=actor();var q=quarantinedPublication(actor);long tenant=actor.tenantId();
        var service=application.getBean(OutboxOperationsService.class);long version=publicationVersion(tenant);
        String payload=jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=?",String.class,tenant);
        var results=parallel(20,()->service.replay(actor,"same-request",q.eventId(),version,"故障修复后重试","ticket-1"));
        assertEquals(1,new HashSet<>(results).size());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox_replay_receipt WHERE tenant_id=?",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='outbox.replay'",tenant));
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",tenant);
        assertEquals(results.getFirst(),service.replay(actor,"same-request",q.eventId(),version,"故障修复后重试","ticket-1"));
        assertCode("IDEMPOTENCY_CONFLICT",()->service.replay(actor,"same-request",q.eventId(),version,"其他原因","ticket-1"));
        assertEquals(payload,jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        assertEquals(10,count("SELECT fencing_token FROM tc_job_lease WHERE tenant_id=?",tenant));
        assertEquals(10,count("SELECT publish_attempt_base FROM tc_outbox WHERE tenant_id=?",tenant));
    }

    @Test void outboxReplayNewRoundRetriesButOldLeaseCannotQuarantineIt() {
        Actor a=actor();var q=quarantinedPublication(a);long tenant=a.tenantId();
        application.getBean(OutboxOperationsService.class).replay(a,"new-round",q.eventId(),publicationVersion(tenant),"恢复","ticket");
        EventPublisherPort network=mock(EventPublisherPort.class);when(network.enabled()).thenReturn(true);
        when(network.publish(any())).thenThrow(new IllegalStateException("网络不确定"));
        var worker=outboxWorker(network,application.getBean(OutboxDeliveryPort.class));
        assertEquals(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.LEASE_LOST,worker.processLease(q.oldLease()));
        verify(network,never()).publish(any());
        assertEquals(List.of(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.RETRY),worker.runBatch(tenant,"new-worker",1));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        assertEquals(11,count("SELECT fencing_token FROM tc_job_lease WHERE tenant_id=?",tenant));
        assertEquals(1,count("SELECT replay_count FROM tc_outbox WHERE tenant_id=?",tenant));
    }

    @Test void outboxReplayConcurrentDifferentCommandsHaveOnlyOneWinner() throws Exception {
        Actor a=actor();var q=quarantinedPublication(a);long version=publicationVersion(a.tenantId());
        var service=application.getBean(OutboxOperationsService.class);var sequence=new AtomicLong();
        var results=parallel(10,()->{
            try { service.replay(a,"request-"+sequence.incrementAndGet(),q.eventId(),version,"恢复","ticket");return "OK"; }
            catch(TradeException conflict) { return conflict.code(); }
        });
        assertEquals(1,results.stream().filter("OK"::equals).count());
        assertEquals(9,results.stream().filter("VERSION_CONFLICT"::equals).count());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox_replay_receipt WHERE tenant_id=?",a.tenantId()));
    }

    @Test void outboxReplayAuditFailureRollsBackReceiptEventAndTask() {
        Actor a=actor();var q=quarantinedPublication(a);long tenant=a.tenantId(),version=publicationVersion(tenant);
        AuditWriter failed=mock(AuditWriter.class);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("审计写入失败")).when(failed).append(any(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        var service=new OutboxOperationsService(application.getBean(com.lrj.trade.core.mapper.OutboxOperationsMapper.class),
            application.getBean(OutboxDeliveryPort.class),application.getBean(OutboxReplaySchedulePort.class),failed);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->new TransactionTemplate(transactions).execute(t->service.replay(a,"request",q.eventId(),version,"恢复","ticket")));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox_replay_receipt WHERE tenant_id=?",tenant));
        assertEquals("REVIEW",jdbc.queryForObject("SELECT status FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        assertEquals("REVIEW",jdbc.queryForObject("SELECT status FROM tc_job_lease WHERE tenant_id=?",String.class,tenant));
        assertEquals(version,publicationVersion(tenant));
        assertEquals(0,count("SELECT publish_attempt_base FROM tc_outbox WHERE tenant_id=?",tenant));
    }

    @Test void outboxReplayTaskQueueIncludesMalformedReferencesWithoutLeakingRawKey() throws Exception {
        long tenant=TENANTS.incrementAndGet();String bad="sensitive-invalid-reference-"+"x".repeat(70);
        new TransactionTemplate(transactions).executeWithoutResult(t->jobLeases.enqueue(tenant,JobType.OUTBOX_PUBLISH,bad));
        EventPublisherPort network=mock(EventPublisherPort.class);when(network.enabled()).thenReturn(true);
        outboxWorker(network,application.getBean(OutboxDeliveryPort.class)).runBatch(tenant,"invalid",1);
        jwt("task-admin","trade.admin",tenant);jwt("task-other","trade.admin",tenant+10000);jwt("task-member","",tenant);
        String route="/api/v1/trade/admin/outbox-publications/tasks";
        assertEquals(403,request("GET",route,"task-member",null).statusCode());
        var result=request("GET",route,"task-admin",null);assertEquals(200,result.statusCode());
        assertTrue(result.body().contains(Checks.hash(bad)));assertFalse(result.body().contains(bad));
        assertTrue(result.body().contains("OUTBOX_INVALID_EVENT_KEY"));
        assertEquals("[]",request("GET",route,"task-other",null).body());
    }

    @Test void outboxReplayHttpRequiresBothScopesAndCannotCrossTenantsOrExposePayload() throws Exception {
        Actor a=actor();var q=quarantinedPublication(a);long tenant=a.tenantId();
        jwt("replay-admin","trade.admin",tenant);jwt("replay-only","trade.events.replay",tenant);
        jwt("replay-both","trade.admin trade.events.replay",tenant);jwt("replay-other","trade.admin trade.events.replay",tenant+10000);
        String path="/api/v1/trade/admin/outbox-publications/"+q.eventId()+":replay";
        String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("expectedVersion",publicationVersion(tenant),"reason","恢复","ticketRef","ticket"));
        assertEquals(403,request("POST",path,"replay-admin",body,"key").statusCode());
        assertEquals(403,request("POST",path,"replay-only",body,"key").statusCode());
        assertEquals(404,request("POST",path,"replay-other",body,"key").statusCode());
        var list=request("GET","/api/v1/trade/admin/outbox-publications","replay-admin",null);
        assertEquals(200,list.statusCode());assertTrue(list.body().contains(q.eventId()));assertFalse(list.body().contains("payload"));
        assertEquals("[]",request("GET","/api/v1/trade/admin/outbox-publications","replay-other",null).body());
        assertEquals(428,versionRequest("POST",path,"replay-both",body,"missing-condition",null).statusCode());
        var response=request("POST",path,"replay-both",body,"key");assertEquals(200,response.statusCode(),response.body());
        assertEquals(response.body(),request("POST",path,"replay-both",body,"key").body());
        assertEquals(412,request("POST",path,"replay-both",body,"different-key").statusCode());
        String current=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("expectedVersion",publicationVersion(tenant),"reason","恢复","ticketRef","ticket"));
        assertEquals(409,request("POST",path,"replay-both",current,"current-wrong-state").statusCode());
    }

    private long publicationVersion(long tenant) { return count("SELECT version FROM tc_outbox WHERE tenant_id=?",tenant); }
    private QuarantinedPublication quarantinedPublication(Actor actor) {
        members.register(actor,"publish");long tenant=actor.tenantId();
        jdbc.update("UPDATE tc_job_lease SET attempts=9,fencing_token=9 WHERE tenant_id=? AND job_type='OUTBOX_PUBLISH'",tenant);
        var lease=jobLeases.claim(tenant,JobType.OUTBOX_PUBLISH,"initial",1,30000).getFirst();
        EventPublisherPort network=mock(EventPublisherPort.class);when(network.enabled()).thenReturn(true);
        when(network.publish(any())).thenThrow(new IllegalStateException("不可用"));
        assertEquals(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.REVIEW,outboxWorker(network,application.getBean(OutboxDeliveryPort.class)).processLease(lease));
        return new QuarantinedPublication(lease.businessKey(),lease);
    }
    private record QuarantinedPublication(String eventId,com.lrj.trade.jobs.model.JobLease oldLease) {}

    @Test void outboxPublishEnqueuesAtomicallyAndDisabledWorkerDoesNotClaim() {
        Actor a=actor();members.register(a,"publish"); long tenant=a.tenantId();
        assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='OUTBOX_PUBLISH'",tenant));
        assertTrue(application.getBean(com.lrj.trade.jobs.OutboxPublishWorker.class).runBatch(tenant,"disabled",1).isEmpty());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND attempts<>0",tenant));
        Actor failedActor=actor(); var failed=mock(JobSchedulerPort.class);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("入队失败")).when(failed).enqueue(anyLong(),eq(JobType.OUTBOX_PUBLISH),anyString());
        var outbox=new Outbox(application.getBean(com.lrj.trade.core.mapper.OutboxMapper.class),failed);
        var service=new MemberService(memberMapper,application.getBean(Commands.class),outbox,auditWriter,clock);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.register(failedActor,"rollback"));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_member WHERE tenant_id=?",failedActor.tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=?",failedActor.tenantId()));
    }

    @Test void outboxPublishAckPersistsLocationAndSentReplaySkipsNetwork() {
        Actor a=actor();members.register(a,"publish");long tenant=a.tenantId();
        String payload=jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=?",String.class,tenant);
        EventPublisherPort network=mock(EventPublisherPort.class);when(network.enabled()).thenReturn(true);
        when(network.publish(any())).thenAnswer(c->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            OutboxEventCatalog.Message message=c.getArgument(0);assertEquals(payload,message.payload());
            return new EventPublisherPort.Receipt(message.eventId(),"test.member",2,17);
        });
        var worker=outboxWorker(network,application.getBean(OutboxDeliveryPort.class));
        assertEquals(List.of(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.DONE),worker.runBatch(tenant,"publisher",1));
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        assertEquals(17,count("SELECT published_offset FROM tc_outbox WHERE tenant_id=?",tenant));
        assertEquals("DONE",jdbc.queryForObject("SELECT status FROM tc_job_lease WHERE tenant_id=?",String.class,tenant));
        jdbc.update("UPDATE tc_job_lease SET status='READY',next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=?",tenant);
        assertEquals(List.of(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.DONE),worker.runBatch(tenant,"replay",1));
        verify(network,times(1)).publish(any());
    }

    @Test void outboxPublishExpiredAckCannotCommitAndReplacementUsesSamePayload() {
        Actor a=actor();members.register(a,"publish");long tenant=a.tenantId();
        var old=jobLeases.claim(tenant,JobType.OUTBOX_PUBLISH,"old",1,30000).getFirst();
        var replacement=new java.util.concurrent.atomic.AtomicReference<com.lrj.trade.jobs.model.JobLease>();
        EventPublisherPort network=mock(EventPublisherPort.class);when(network.enabled()).thenReturn(true);
        var payloads=new ArrayList<String>();
        when(network.publish(any())).thenAnswer(c->{
            OutboxEventCatalog.Message message=c.getArgument(0);payloads.add(message.payload());
            if(replacement.get()==null) { expireLease(old);replacement.set(jobLeases.claim(tenant,JobType.OUTBOX_PUBLISH,"new",1,30000).getFirst()); }
            return new EventPublisherPort.Receipt(message.eventId(),"test.member",0,1);
        });
        var worker=outboxWorker(network,application.getBean(OutboxDeliveryPort.class));
        assertEquals(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.LEASE_LOST,worker.processLease(old));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        assertNull(jdbc.queryForObject("SELECT published_at FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        assertEquals(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.DONE,worker.processLease(replacement.get()));
        assertEquals(2,payloads.size());assertEquals(payloads.getFirst(),payloads.getLast());
    }

    @Test void outboxPublishUncertainSendRetriesBoundedlyAndInvalidV0IsIsolated() {
        Actor a=actor();members.register(a,"publish");long tenant=a.tenantId();
        EventPublisherPort network=mock(EventPublisherPort.class);when(network.enabled()).thenReturn(true);
        when(network.publish(any())).thenThrow(new IllegalStateException("敏感Broker地址和凭据不得保存"));
        var worker=outboxWorker(network,application.getBean(OutboxDeliveryPort.class));
        assertEquals(List.of(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.RETRY),worker.runBatch(tenant,"retry",1));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        assertEquals("OUTBOX_SEND_UNCERTAIN",jdbc.queryForObject("SELECT last_publish_error FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        jdbc.update("UPDATE tc_job_lease SET attempts=9,fencing_token=9,next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=?",tenant);
        assertEquals(List.of(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.REVIEW),worker.runBatch(tenant,"exhaust",1));
        assertEquals("REVIEW",jdbc.queryForObject("SELECT status FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        Actor v0=actor();new TransactionTemplate(transactions).executeWithoutResult(t->application.getBean(Outbox.class).append(v0.tenantId(),"order.paid.internal.v0","1"));
        String id=jdbc.queryForObject("SELECT event_id FROM tc_outbox WHERE tenant_id=?",String.class,v0.tenantId());
        new TransactionTemplate(transactions).executeWithoutResult(t->jobLeases.enqueue(v0.tenantId(),JobType.OUTBOX_PUBLISH,id));
        assertEquals(List.of(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.REVIEW),worker.runBatch(v0.tenantId(),"invalid",1));
        verify(network,times(2)).publish(any());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM tc_outbox WHERE tenant_id=?",String.class,v0.tenantId()));
    }

    @Test void outboxPublishBackfillSkipsV0AndDoesNotReviveTerminalJobs() {
        Actor a=actor();members.register(a,"publish");long tenant=a.tenantId();
        new TransactionTemplate(transactions).executeWithoutResult(t->application.getBean(Outbox.class).append(tenant,"member.internal.v0","1"));
        jdbc.update("DELETE FROM tc_job_lease WHERE tenant_id=? AND job_type='OUTBOX_PUBLISH'",tenant);
        var backfill=application.getBean(OutboxPublishBackfillService.class);long cursor=0,next;
        do { next=backfill.enqueuePage(tenant,cursor,1);if(next==cursor) break;cursor=next; } while(true);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=?",tenant));
        jdbc.update("UPDATE tc_job_lease SET status='REVIEW' WHERE tenant_id=?",tenant);
        backfill.enqueuePage(tenant,0,100);
        assertEquals("REVIEW",jdbc.queryForObject("SELECT status FROM tc_job_lease WHERE tenant_id=?",String.class,tenant));
    }

    @Test void outboxPublishAckThenDatabaseFailureRollsBackLocalSuccessAndResendsSameEvent() {
        Actor a=actor();members.register(a,"publish");long tenant=a.tenantId();
        EventPublisherPort network=mock(EventPublisherPort.class);when(network.enabled()).thenReturn(true);
        var payloads=new ArrayList<String>();
        when(network.publish(any())).thenAnswer(c->{
            OutboxEventCatalog.Message m=c.getArgument(0);payloads.add(m.payload());
            return new EventPublisherPort.Receipt(m.eventId(),"test.member",0,1);
        });
        var real=application.getBean(OutboxDeliveryPort.class);
        OutboxDeliveryPort failed=mock(OutboxDeliveryPort.class);
        when(failed.lock(anyLong(),anyString())).thenAnswer(c->real.lock(c.getArgument(0),c.getArgument(1)));
        doAnswer(c->{real.sent(c.getArgument(0),c.getArgument(1),c.getArgument(2));throw new org.springframework.dao.DataIntegrityViolationException("模拟ACK后本地事务失败");})
            .when(failed).sent(anyLong(),anyString(),any());
        var lease=jobLeases.claim(tenant,JobType.OUTBOX_PUBLISH,"old",1,30000).getFirst();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->outboxWorker(network,failed).processLease(lease));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        assertNull(jdbc.queryForObject("SELECT published_at FROM tc_outbox WHERE tenant_id=?",String.class,tenant));
        assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM tc_job_lease WHERE tenant_id=?",String.class,tenant));
        expireLease(lease);
        assertEquals(List.of(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.DONE),outboxWorker(network,real).runBatch(tenant,"replacement",1));
        assertEquals(2,payloads.size());assertEquals(payloads.getFirst(),payloads.getLast());
    }

    @Test void outboxPublishMalformedLongKeyIsReviewedWithoutEndlessLeaseRecovery() {
        long tenant=TENANTS.incrementAndGet();
        new TransactionTemplate(transactions).executeWithoutResult(t->jobLeases.enqueue(tenant,JobType.OUTBOX_PUBLISH,"x".repeat(100)));
        EventPublisherPort network=mock(EventPublisherPort.class);when(network.enabled()).thenReturn(true);
        var worker=outboxWorker(network,application.getBean(OutboxDeliveryPort.class));
        assertEquals(List.of(com.lrj.trade.jobs.OutboxPublishWorker.Outcome.REVIEW),worker.runBatch(tenant,"invalid",1));
        assertEquals("OUTBOX_INVALID_EVENT_KEY",jdbc.queryForObject("SELECT last_error FROM tc_job_lease WHERE tenant_id=?",String.class,tenant));
        verify(network,never()).publish(any());
    }

    private com.lrj.trade.jobs.OutboxPublishWorker outboxWorker(EventPublisherPort publisher,OutboxDeliveryPort delivery) {
        return new com.lrj.trade.jobs.OutboxPublishWorker(jobLeases,delivery,publisher,transactions);
    }

    @Test void inventoryV1SnapshotsFollowActualLedgerAndDoNotReplayOrRebuild() {
        Fixture f=fixture(10); long tenant=f.actor().tenantId();
        inventory.reserve(f.actor(),"v1-reserve",f.warehouse(),f.sku(),2); inventory.confirm(f.actor(),"v1-confirm","v1-reserve");
        var payloads=jdbc.queryForList("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.inventory-changed.v1' ORDER BY id",String.class,tenant);
        assertEquals(3,payloads.size()); var event=InventoryChangedEventCodec.decode(payloads.getLast());
        assertEquals(f.warehouse()+":"+f.sku(),event.aggregateId()); assertEquals(8,event.data().available());
        assertEquals(0,event.data().reserved()); assertEquals(2,event.data().sold()); assertEquals(10,event.data().stockTotal());
        assertEquals(balance(f).version(),event.aggregateVersion());
        inventory.confirm(f.actor(),"other-command","v1-reserve");
        assertEquals(payloads,jdbc.queryForList("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.inventory-changed.v1' ORDER BY id",String.class,tenant));
        inventory.adjust(f.actor(),"later",f.warehouse(),f.sku(),1,"private adjustment reason");
        assertEquals(payloads,jdbc.queryForList("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.inventory-changed.v1' ORDER BY id LIMIT 3",String.class,tenant));
    }

    @Test void inventoryV1CrossWarehouseSnapshotsAreAtomicAndDamagedItemsDoNotEmit() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"return-v1",2);
        Actor source=new Actor(tenant,"https://warehouse.test","return-v1"); fulfillment.deliver(source,"delivery",order.orderNo(),"proof",clock.instant());
        long target=Long.parseLong(inventory.warehouse(f.actor(),"target","target","目标仓"));
        long before=count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.inventory-changed.v1'",tenant);
        returns.inspect(source,"return",order.orderNo(),Long.parseLong(order.items().getFirst().itemId()),target,1,1,"proof");
        var last=jdbc.queryForList("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.inventory-changed.v1' ORDER BY id DESC LIMIT 2",String.class,tenant)
            .stream().map(InventoryChangedEventCodec::decode).toList();
        assertTrue(last.stream().anyMatch(e->e.data().operationNo().endsWith(":OUT"))); assertTrue(last.stream().anyMatch(e->e.data().operationNo().endsWith(":IN")));
        assertEquals(2,last.stream().mapToLong(e->e.data().stockTotal()).sum());
        assertEquals(before+2,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.inventory-changed.v1'",tenant));
        returns.inspect(source,"return",order.orderNo(),Long.parseLong(order.items().getFirst().itemId()),target,1,1,"proof");
        returns.inspect(source,"damaged",order.orderNo(),Long.parseLong(order.items().getFirst().itemId()),target,1,0,"proof");
        assertEquals(before+2,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.inventory-changed.v1'",tenant));
    }

    @Test void inventoryV1SecondWarehouseEventFailureRollsBackBothBalancesAndReceipt() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"return-event-failure",1);
        Actor source=new Actor(tenant,"https://warehouse.test","return-v1"); fulfillment.deliver(source,"delivery",order.orderNo(),"proof",clock.instant());
        long target=Long.parseLong(inventory.warehouse(f.actor(),"target","target","目标仓")); var before=balance(f);
        long eventsBefore=count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.inventory-changed.v1'",tenant);
        Outbox failed=mock(Outbox.class); doAnswer(call->{
            InventoryChangedEvent event=call.getArgument(0);
            if(event.data().warehouseId().equals(Long.toString(target))) throw new org.springframework.dao.DataIntegrityViolationException("模拟目标仓事件写失败");
            application.getBean(Outbox.class).inventoryChanged(event); return null;
        }).when(failed).inventoryChanged(any());
        var inventoryMapper=application.getBean(com.lrj.trade.inventory.mapper.InventoryMapper.class);
        var writer=new com.lrj.trade.inventory.InventoryEventWriter(inventoryMapper,failed,clock);
        var port=new com.lrj.trade.inventory.InventoryReturnService(inventoryMapper,application.getBean(com.lrj.trade.inventory.mapper.InventoryReturnMapper.class),application.getBean(Outbox.class),writer);
        var service=new com.lrj.trade.order.OrderReturnService(application.getBean(com.lrj.trade.order.mapper.OrderMapper.class),
            application.getBean(com.lrj.trade.order.mapper.FulfillmentMapper.class),application.getBean(com.lrj.trade.order.mapper.ReturnReceiptMapper.class),
            port,application.getBean(Commands.class),auditWriter,application.getBean(Outbox.class),clock);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.inspect(source,"return",order.orderNo(),Long.parseLong(order.items().getFirst().itemId()),target,1,1,"proof"));
        assertEquals(before,balance(f)); assertEquals(0,count("SELECT COUNT(*) FROM tc_inventory_balance WHERE tenant_id=? AND warehouse_id=?",tenant,target));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",tenant));
        assertEquals(eventsBefore,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.inventory-changed.v1'",tenant));
    }

    @Test void refundEvidenceV1TracksReserveReleaseSuccessAndPermanentReplay() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"refund-evidence",1);
        var first=requestRefund(f,order,"first",40); var held=lastRefundEvidence(tenant);
        assertEquals("REFUND_RESERVED",held.data().changeReason()); assertEquals(40,held.data().pendingRefundAmount());
        assertEquals(100,held.data().netPaidAmount()); assertEquals(60,held.data().conservativeEligibleAmount());
        assertEquals(orders.read(f.actor(),order.orderNo()).version(),held.aggregateVersion());
        requestRefund(f,order,"first",40); assertEquals(held,lastRefundEvidence(tenant));
        approveRefund(f,first,false,1); var released=lastRefundEvidence(tenant);
        assertEquals("REFUND_RELEASED",released.data().changeReason()); assertEquals(0,released.data().pendingRefundAmount());
        assertEquals(100,released.data().conservativeEligibleAmount()); assertTrue(released.aggregateVersion()>held.aggregateVersion());
        var second=requestRefund(f,order,"second",60); var nextHeld=lastRefundEvidence(tenant);
        approveRefund(f,second,true,1); prepareRefund(f,second); assertEquals(nextHeld,lastRefundEvidence(tenant));
        completeRefund(f,second,"provider-v1"); var changed=lastRefundEvidence(tenant);
        var success=RefundEvidenceEventCodec.decode(jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.refund-succeeded.v1'",String.class,tenant));
        assertEquals(changed.data(),success.data()); assertEquals(changed.aggregateVersion(),success.aggregateVersion());
        assertEquals(changed.occurredAt(),success.occurredAt()); assertNotEquals(changed.eventId(),success.eventId());
        assertEquals(60,success.data().cumulativeRefundedAmount()); assertEquals(0,success.data().pendingRefundAmount()); assertEquals(40,success.data().netPaidAmount());
        completeRefund(f,second,"provider-v1"); assertEquals(changed,lastRefundEvidence(tenant));
        assertEquals(4,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-evidence-changed.v1'",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.refund-succeeded.v1'",tenant));
    }

    @Test void refundEvidenceV1ExpiryAndReliableFailureReleaseCurrentSnapshot() {
        for(boolean expiry:List.of(true,false)) {
            Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"released-evidence",1);
            var refund=requestRefund(f,order,"refund",100);
            if(expiry) {
                var later=clock.instant().plus(java.time.Duration.ofDays(2)); when(clock.instant()).thenReturn(later);
                new TransactionTemplate(transactions).executeWithoutResult(tx->refunds.expireApproval(tenant,refund.refundNo()));
            } else {
                approveRefund(f,refund,true,1); prepareRefund(f,refund);
                new TransactionTemplate(transactions).executeWithoutResult(tx->refunds.confirmFailed(tenant,refund.refundNo(),"test:reliable-failure"));
            }
            var event=lastRefundEvidence(tenant); assertEquals("REFUND_RELEASED",event.data().changeReason());
            assertEquals(0,event.data().pendingRefundAmount()); assertEquals(0,event.data().cumulativeRefundedAmount());
            assertEquals(100,event.data().conservativeEligibleAmount()); assertEquals(orders.read(f.actor(),order.orderNo()).version(),event.aggregateVersion());
            assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.refund-succeeded.v1'",tenant));
            fixedBusinessClock();
        }
    }

    @Test void refundEvidenceV1EveryWritePathRollsBackOnEventFailure() {
        for(String action:List.of("REQUEST","REJECT","EXPIRE","FAILED","SUCCEEDED")) {
            Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"event-failure",1);
            Outbox failed=mock(Outbox.class);
            doAnswer(call->{
                RefundEvidenceEvent event=call.getArgument(0);
                // 成功故障特意发生在第二份事件，证明第一份已插入事件也随事务回滚。
                if(action.equals("SUCCEEDED") && event.eventType().equals("trade.order-evidence-changed.v1")) {
                    application.getBean(Outbox.class).refundEvidence(event); return null;
                }
                throw new org.springframework.dao.DataIntegrityViolationException("模拟退款事件失败");
            }).when(failed).refundEvidence(any());
            var service=new com.lrj.trade.refund.RefundService(application.getBean(com.lrj.trade.refund.mapper.RefundMapper.class),
                application.getBean(com.lrj.trade.order.OrderRefundPort.class),refundFunds,application.getBean(com.lrj.trade.refund.RefundPolicyPort.class),
                application.getBean(Commands.class),auditWriter,failed,clock,jobLeases);
            if(action.equals("REQUEST")) {
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.request(f.actor(),"refund",order.orderNo(),100,
                    List.of(new com.lrj.trade.refund.model.RefundSelection("GOODS",order.items().getFirst().itemId(),0,100)),"reason"));
                assertEquals(order,orders.read(f.actor(),order.orderNo())); assertEquals(0,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",tenant));
                assertApplicationAmounts(f,0,0);
            } else {
                var refund=requestRefund(f,order,"refund",100);
                if(Set.of("FAILED","SUCCEEDED").contains(action)) { approveRefund(f,refund,true,1); prepareRefund(f,refund); }
                if(action.equals("EXPIRE")) { var later=clock.instant().plus(java.time.Duration.ofDays(2)); when(clock.instant()).thenReturn(later); }
                var before=orders.read(f.actor(),order.orderNo()); var event=lastRefundEvidence(tenant); var refundBefore=refunds.read(f.actor(),refund.refundNo());
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                    switch(action) {
                        case "REJECT" -> service.applyApproval(new Actor(tenant,"https://identity.test","approver"),refund.refundNo(),refund.approvalRef(),1,false,"test:approval");
                        case "EXPIRE" -> service.expireApproval(tenant,refund.refundNo());
                        case "FAILED" -> service.confirmFailed(tenant,refund.refundNo(),"test:reliable-failure");
                        case "SUCCEEDED" -> service.confirmSucceeded(tenant,refund.refundNo(),"provider-failure",100,"CNY",clock.instant(),"proof");
                        default -> throw new IllegalStateException(action);
                    }
                }));
                assertEquals(before,orders.read(f.actor(),order.orderNo())); assertEquals(refundBefore,refunds.read(f.actor(),refund.refundNo()));
                assertEquals(event,lastRefundEvidence(tenant)); assertApplicationAmounts(f,100,0);
            }
            assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.refund-succeeded.v1'",tenant));
            fixedBusinessClock();
        }
    }

    private RefundEvidenceEvent lastRefundEvidence(long tenant) {
        return RefundEvidenceEventCodec.decode(jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-evidence-changed.v1' ORDER BY id DESC LIMIT 1",String.class,tenant));
    }

    @Test void lifecycleV1CompletionIsIndependentPermanentAndSurvivesRefund() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"complete-v1",1);
        Actor source=new Actor(tenant,"https://warehouse.test","trusted-fulfillment");
        fulfillment.deliver(source,"delivery",order.orderNo(),"proof:delivery",clock.instant());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-completed.v1'",tenant));
        fulfillment.complete(source,"completion",order.orderNo(),"proof:completion",clock.instant());
        String payload=jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-completed.v1'",String.class,tenant);
        var event=OrderCompletedEventCodec.decode(payload); var completed=orders.read(f.actor(),order.orderNo());
        assertEquals(completed.version(),event.aggregateVersion()); assertEquals(completed.completedAt(),event.data().completedAt());
        assertEquals(order.orderId(),event.aggregateId()); assertEquals(members.me(f.actor()).memberId(),event.data().memberId());
        var refund=requestRefund(f,order,"complete-refund",100); approveRefund(f,refund,true,1); prepareRefund(f,refund); completeRefund(f,refund,"completion-refund");
        fulfillment.complete(source,"completion",order.orderNo(),"proof:completion",clock.instant());
        assertEquals(payload,jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-completed.v1'",String.class,tenant));
    }

    @Test void lifecycleV1CancellationWaitsForReliableCloseAndDoesNotLeakReason() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(); var direct=factOrder(f,"direct-v1");
        String privateReason="私人电话 13912345678"; var cancelled=orders.cancel(f.actor(),"cancel-direct",direct.orderNo(),privateReason);
        var first=OrderCancelledEventCodec.decode(jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-cancelled.v1'",String.class,tenant));
        assertEquals("CANCEL_REQUEST",first.data().reason()); assertEquals(cancelled.version(),first.aggregateVersion());
        assertFalse(OrderCancelledEventCodec.encode(first).contains(privateReason));
        var pending=factOrder(f,"pending-v1"); var payment=createPayment(f.actor(),"pay",pending.orderNo(),"TEST_A","return");
        paymentDispatch.prepare(tenant,payment.activeAttemptNo()); orders.cancel(f.actor(),"cancel-pending",pending.orderNo(),privateReason);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-cancelled.v1'",tenant));
        paymentObservations.closed(tenant,payment.activeAttemptNo(),"test:nonpayable"); paymentObservations.closed(tenant,payment.activeAttemptNo(),"test:repeat");
        String payload=jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='trade.order-cancelled.v1'",String.class,tenant,pending.orderId());
        var last=OrderCancelledEventCodec.decode(payload); assertEquals("PAYMENT_NONPAYABLE",last.data().reason());
        assertEquals(orders.read(f.actor(),pending.orderNo()).version(),last.aggregateVersion());
        assertEquals(2,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-cancelled.v1'",tenant));
    }

    @Test void lifecycleV1CompletionWriteFailureRollsBackCompletionFactAndOrder() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"completion-failure",1);
        Actor source=new Actor(tenant,"https://warehouse.test","trusted-fulfillment"); fulfillment.deliver(source,"delivery",order.orderNo(),"proof",clock.instant());
        var before=orders.read(f.actor(),order.orderNo()); Outbox failed=mock(Outbox.class);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("模拟完成事件失败")).when(failed).orderCompleted(any());
        var service=new com.lrj.trade.order.OrderFulfillmentService(application.getBean(com.lrj.trade.order.mapper.OrderMapper.class),
            application.getBean(com.lrj.trade.order.mapper.FulfillmentMapper.class),application.getBean(Commands.class),auditWriter,failed,clock);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.complete(source,"completion",order.orderNo(),"proof",clock.instant()));
        assertEquals(before,orders.read(f.actor(),order.orderNo()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_order_fulfillment WHERE tenant_id=? AND completed_at IS NOT NULL",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-completed.v1'",tenant));
        fulfillment.complete(source,"completion",order.orderNo(),"proof",clock.instant());
    }

    @Test void lifecycleV1BothCancellationPathsRollBackWhenEventWriteFails() {
        for(boolean hasPayment:List.of(false,true)) {
            Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=factOrder(f,"cancel-failure"); Outbox failed=mock(Outbox.class);
            doThrow(new org.springframework.dao.DataIntegrityViolationException("模拟取消事件失败")).when(failed).orderCancelled(any());
            var broken=orderServiceWithOutbox(failed);
            if(!hasPayment) {
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->broken.cancel(f.actor(),"cancel",order.orderNo(),"reason"));
                assertEquals(order,orders.read(f.actor(),order.orderNo()));
            } else {
                var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return"); paymentDispatch.prepare(tenant,payment.activeAttemptNo());
                orders.cancel(f.actor(),"cancel",order.orderNo(),"reason"); var before=orders.read(f.actor(),order.orderNo());
                var observer=new com.lrj.trade.payment.PaymentObservationService(application.getBean(com.lrj.trade.payment.mapper.PaymentMapper.class),
                    application.getBean(com.lrj.trade.payment.mapper.ChannelMapper.class),broken,application.getBean(Outbox.class),clock,jobLeases);
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->observer.closed(tenant,payment.activeAttemptNo(),"test:nonpayable")));
                assertEquals(before,orders.read(f.actor(),order.orderNo())); assertEquals("UNKNOWN",payments.read(f.actor(),payment.paymentNo()).attemptStatus());
            }
            assertEquals(1,balance(f).reserved()); assertEquals(0,balance(f).available());
            assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-cancelled.v1'",tenant));
        }
    }

    private com.lrj.trade.order.OrderService orderServiceWithOutbox(Outbox outbox) {
        return new com.lrj.trade.order.OrderService(application.getBean(com.lrj.trade.order.mapper.OrderMapper.class),application.getBean(Commands.class),quotes,
            application.getBean(com.lrj.trade.member.MemberTradingPort.class),application.getBean(com.lrj.trade.order.mapper.OrderAttributionMapper.class),application.getBean(com.lrj.trade.order.OrderAttributionReplayReader.class),
            application.getBeanProvider(com.lrj.trade.order.OrderAttributionVerifierPort.class),inventory,merchants,application.getBean(com.lrj.trade.order.AddressProtector.class),
            auditWriter,outbox,clock,application.getBean(AdmissionService.class),tradeFacts,jobLeases,900);
    }

    @Test void paidV1UsesConfirmationTimeAfterWaitingForMemberFactLock() throws Exception {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(),member=Long.parseLong(members.me(f.actor()).memberId());
        var order=factOrder(f,"v1-lock-time"); var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        Instant before=clock.instant(),after=before.plusSeconds(1); var time=new java.util.concurrent.atomic.AtomicReference<>(before);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var sampled=new CountDownLatch(1);
        when(clock.instant()).thenAnswer(call->{ var value=time.get(); if(Thread.currentThread().getName().equals("paid-v1-waiter")) sampled.countDown(); return value; });
        try(var holder=Executors.newSingleThreadExecutor(); var payer=Executors.newSingleThreadExecutor(r->new Thread(r,"paid-v1-waiter"))) {
            var lock=holder.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForObject("SELECT member_id FROM tc_member_trade_fact WHERE tenant_id=? AND member_id=? FOR UPDATE",Long.class,tenant,member);
                locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放会员锁"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }));
            try {
                assertTrue(locked.await(5,TimeUnit.SECONDS)); var paid=payer.submit(()->receive(f,payment,"v1-lock-provider",100,"CNY"));
                assertTrue(sampled.await(5,TimeUnit.SECONDS)); assertThrows(TimeoutException.class,()->paid.get(100,TimeUnit.MILLISECONDS));
                time.set(after); release.countDown(); var receipt=paid.get(10,TimeUnit.SECONDS);
                assertEquals(before,receipt.confirmedAt());
                var event=OrderPaidEventCodec.decode(jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-paid.v1'",String.class,tenant));
                assertEquals(after,event.occurredAt()); assertEquals(after,event.data().paidAt());
                assertEquals(orders.read(f.actor(),order.orderNo()).paidAt(),event.data().paidAt());
            } finally { release.countDown(); lock.get(10,TimeUnit.SECONDS); }
        }
    }

    @Test void paidV1WriteFailureRollsBackRealFundsAndEveryDomain() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=factOrder(f,"v1-failure");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        Outbox failed=mock(Outbox.class); doThrow(new org.springframework.dao.DataIntegrityViolationException("模拟V1写入失败")).when(failed).orderPaid(any());
        var service=new com.lrj.trade.payment.PaymentObservationService(
            application.getBean(com.lrj.trade.payment.mapper.PaymentMapper.class),application.getBean(com.lrj.trade.payment.mapper.ChannelMapper.class),
            application.getBean(com.lrj.trade.order.OrderPaymentPort.class),failed,clock,jobLeases);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->
            service.received(tenant,payment.activeAttemptNo(),"failed-v1",100,"CNY",clock.instant(),"test:v1-failure")));
        assertFalse(members.me(f.actor()).everPaid()); assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,balance(f).reserved()); assertEquals(0,balance(f).sold());
        for(String table:List.of("tc_payment_receipt","tc_money_ledger","tc_member_paid_order")) assertEquals(0,count("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",tenant));
        assertNull(jdbc.queryForObject("SELECT succeeded_receipt_id FROM tc_payment_intent WHERE tenant_id=?",String.class,tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-paid.v1'",tenant));
        receive(f,payment,"failed-v1",100,"CNY");
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-paid.v1'",tenant));
    }

    @Test void paidV1UsesOrderVersionAndPermanentFirstFactAndNeverRebuildsPayload() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId();
        var first=paidRefundOrder(f,"v1-first",1); var second=paidRefundOrder(f,"v1-second",1);
        var texts=jdbc.queryForList("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-paid.v1' ORDER BY id",String.class,tenant);
        assertEquals(2,texts.size()); var a=OrderPaidEventCodec.decode(texts.get(0)); var b=OrderPaidEventCodec.decode(texts.get(1));
        assertEquals(first.orderId(),a.aggregateId()); assertEquals(first.version(),a.aggregateVersion());
        assertEquals(second.orderId(),b.aggregateId()); assertEquals(second.version(),b.aggregateVersion());
        assertTrue(a.data().isFirstConfirmedPaidOrder()); assertFalse(b.data().isFirstConfirmedPaidOrder());
        assertEquals(first.paidAt(),a.data().paidAt()); assertEquals(a.data().paidAt(),a.occurredAt());
        assertEquals(members.me(f.actor()).memberId(),a.data().memberId()); assertEquals(100,a.data().paidAmount());
        assertEquals(a.eventId(),jdbc.queryForObject("SELECT event_id FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-paid.v1' AND aggregate_id=?",String.class,tenant,first.orderId()));
        var refund=requestRefund(f,first,"v1-refund",100); approveRefund(f,refund,true,1); prepareRefund(f,refund); completeRefund(f,refund,"v1-refunded");
        assertEquals(texts,jdbc.queryForList("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-paid.v1' ORDER BY id",String.class,tenant));
        assertTrue(orderEvidence(f,first).evidenceVersion()>a.aggregateVersion());
    }

    @Test void memberV1EventRemainsImmutableAcrossReplayAndProfileChanges() throws Exception {
        Actor actor=actor(); var at=clock.instant(); String id=members.register(actor,"私密展示名称");
        String payload=jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.member-registered.v1'",String.class,actor.tenantId());
        var json=tools.jackson.databind.json.JsonMapper.builder().build(); var event=json.readTree(payload);
        assertEquals(id,event.path("aggregateId").asText()); assertEquals(id,event.path("data").path("memberId").asText());
        assertEquals(Long.toString(actor.tenantId()),event.path("tenantId").asText()); assertEquals("MEMBER",event.path("aggregateType").asText());
        assertEquals(0,event.path("aggregateVersion").asLong()); assertEquals(1,event.path("schemaVersion").asInt());
        assertEquals("transaction-center",event.path("producer").asText());
        assertEquals(at,Instant.parse(event.path("occurredAt").asText()));
        assertEquals(Instant.parse(members.me(actor).registeredAt()),Instant.parse(event.path("data").path("registeredAt").asText()));
        assertEquals("AUTHENTICATED_IDENTITY",event.path("data").path("registrationSource").asText());
        UUID.fromString(event.path("eventId").asText()); UUID.fromString(event.path("traceId").asText());
        assertFalse(payload.contains(actor.subject())); assertFalse(payload.contains(actor.issuer())); assertFalse(payload.contains("私密展示名称"));
        assertEquals(event.path("eventId").asText(),jdbc.queryForObject("SELECT event_id FROM tc_outbox WHERE tenant_id=?",String.class,actor.tenantId()));
        when(clock.instant()).thenReturn(at.plusSeconds(90));
        assertEquals(id,members.register(actor,"重试不重建")); members.updateProfile(actor,"change",0,"后来的名字");
        assertEquals(payload,jdbc.queryForObject("SELECT payload FROM tc_outbox WHERE tenant_id=? AND event_type='trade.member-registered.v1'",String.class,actor.tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.member-registered.v1'",actor.tenantId()));
    }

    @Test void memberV1OutboxFailureRollsBackRegistrationAndRejectsMissingTransaction() {
        Actor actor=actor(); var outbox=application.getBean(Outbox.class);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->outbox.memberRegistered(actor.tenantId(),1,clock.instant()));
        var failedMapper=mock(com.lrj.trade.core.mapper.OutboxMapper.class);
        when(failedMapper.insertV1Event(anyLong(),anyString(),anyString(),anyString(),anyString())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("模拟事件持久化失败"));
        var service=new MemberService(memberMapper,application.getBean(Commands.class),new Outbox(failedMapper,jobLeases),auditWriter,clock);
        // 只替换事件持久化故障，注册/消费事实/命令仍走真实MySQL事务。
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.register(actor,"失败注册"));
        for(String table:List.of("tc_member","tc_member_trade_fact","tc_command","tc_outbox"))
            assertEquals(0,count("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",actor.tenantId()));
    }

    @Test void firstEvidenceUsesPermanentGlobalFirstAndLocalConfirmationWindow() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(),member=Long.parseLong(members.me(f.actor()).memberId());
        Instant firstAt=clock.instant().plusSeconds(10); when(clock.instant()).thenReturn(firstAt);
        var first=paidRefundOrder(f,"first-window",1);
        Instant secondAt=firstAt.plusSeconds(10); when(clock.instant()).thenReturn(secondAt); var second=paidRefundOrder(f,"second-window",1);
        var found=referralEvidence.first(tenant,member,firstAt,firstAt.plusMillis(1),"FIRST_CONFIRMED_PAID_ORDER_V1");
        assertEquals("READY",found.state()); assertEquals(firstAt,found.firstConfirmedAt()); assertEquals(first.orderId(),found.evidence().orderId());
        assertEquals("NOT_FOUND",referralEvidence.first(tenant,member,firstAt.minusMillis(1),firstAt,"FIRST_CONFIRMED_PAID_ORDER_V1").state());
        assertEquals("NOT_FOUND",referralEvidence.first(tenant,member,firstAt.plusMillis(1),secondAt.plusMillis(1),"FIRST_CONFIRMED_PAID_ORDER_V1").state());
        assertFalse(orderEvidence(f,second).isFirstConfirmedPaidOrder());
        var refund=requestRefund(f,first,"all-first",100); approveRefund(f,refund,true,1); prepareRefund(f,refund); completeRefund(f,refund,"first-window-refund");
        var after=referralEvidence.first(tenant,member,firstAt,firstAt.plusMillis(1),"FIRST_CONFIRMED_PAID_ORDER_V1");
        assertEquals(first.orderId(),after.evidence().orderId()); assertEquals(0,after.evidence().netPaidAmount()); assertTrue(after.evidence().isFirstConfirmedPaidOrder());
        assertEquals(firstAt,after.firstConfirmedAt()); assertTrue(after.evidence().evidenceVersion()>found.evidence().evidenceVersion());
        // 截止时间只用于选单，窗口结束后的退款仍反映在当前金额中。
        assertTrue(after.evidence().asOf().isAfter(firstAt.plusMillis(1)));
    }

    @Test void firstEvidenceMissingAndInvalidWindowNeverInventsQualification() {
        Fixture f=orderFixture(1); long tenant=f.actor().tenantId(),member=Long.parseLong(members.me(f.actor()).memberId()); var at=clock.instant();
        var missing=referralEvidence.first(tenant,member,at,at.plusSeconds(1),"FIRST_CONFIRMED_PAID_ORDER_V1");
        assertEquals("NOT_FOUND",missing.state()); assertNull(missing.evidence()); assertNull(missing.firstConfirmedAt());
        assertEquals("NOT_FOUND",referralEvidence.first(tenant+10000,member,at,at.plusSeconds(1),"FIRST_CONFIRMED_PAID_ORDER_V1").state());
        assertCode("INVALID_ARGUMENT",()->referralEvidence.first(tenant,member,at,at,"FIRST_CONFIRMED_PAID_ORDER_V1"));
        assertCode("INVALID_ARGUMENT",()->referralEvidence.first(tenant,member,at.plusNanos(1),at.plusNanos(2),"FIRST_CONFIRMED_PAID_ORDER_V1"));
        assertCode("INVALID_ARGUMENT",()->referralEvidence.first(tenant,member,null,at,"FIRST_CONFIRMED_PAID_ORDER_V1"));
        assertCode("INVALID_ARGUMENT",()->referralEvidence.first(tenant,member,at,at.plusSeconds(1),"CHANNEL_EARLIEST"));
    }

    @Test void firstEvidenceRejectsMisboundPointerAndNonFirstOrder() {
        Fixture f=paymentFixture(3); long tenant=f.actor().tenantId(),member=Long.parseLong(members.me(f.actor()).memberId());
        paidRefundOrder(f,"pointer-first",1); var second=paidRefundOrder(f,"pointer-second",1);
        Actor otherActor=new Actor(tenant,"https://identity.test","other-first-owner");
        members.register(otherActor,"其他首单会员"); var other=paidRefundOrder(new Fixture(otherActor,f.warehouse(),f.sku()),"pointer-other",1);
        var at=clock.instant();
        // 仅替换损坏的会员指针，其余订单、收款和首单标志仍从真实数据库读取。
        for(var candidate:List.of(second,other)) {
            var service=new OrderReferralEvidenceService(orderEvidencePort,paymentEvidencePort,memberPaidEvidencePort,transactions,clock,
                (t,m)->List.of(new com.lrj.trade.member.model.CustomerFact(Long.toString(member),at,true,candidate.orderId(),at,2)));
            assertCode("EVIDENCE_UNAVAILABLE",()->service.first(tenant,member,at,at.plusSeconds(1),"FIRST_CONFIRMED_PAID_ORDER_V1"));
        }
    }

    @Test void firstEvidenceRetainsOneSnapshotWhenRefundCommitsAfterFirstPointerRead() {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"first-snapshot",1);
        long tenant=f.actor().tenantId(),member=Long.parseLong(members.me(f.actor()).memberId()); var at=clock.instant(); var before=orderEvidence(f,order);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var service=new OrderReferralEvidenceService(orderEvidencePort,paymentEvidencePort,memberPaidEvidencePort,transactions,clock,(t,m)->{
                var facts=customerFactPort.read(t,m);
                try { pool.submit(()->requestRefund(f,order,"first-pointer-race",100)).get(10,TimeUnit.SECONDS); }
                catch(Exception e) { throw new IllegalStateException(e); }
                return facts;
            });
            var old=service.first(tenant,member,at,at.plusSeconds(1),"FIRST_CONFIRMED_PAID_ORDER_V1");
            assertEquals(before,old.evidence());
        }
        var current=referralEvidence.first(tenant,member,at,at.plusSeconds(1),"FIRST_CONFIRMED_PAID_ORDER_V1");
        assertEquals(100,current.evidence().pendingRefundAmount()); assertTrue(current.evidence().evidenceVersion()>before.evidenceVersion());
    }

    @Test void firstEvidenceHttpUsesDedicatedIdentityAndKeepsExistingBatchShape() throws Exception {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"first-http",1); long tenant=f.actor().tenantId();
        var json=tools.jackson.databind.json.JsonMapper.builder().build(); String path="/internal/v1/first-order-referral-evidence:query";
        String body=json.writeValueAsString(Map.of("memberId",members.me(f.actor()).memberId(),"from",clock.instant().toString(),"to",clock.instant().plusSeconds(1).toString(),"firstOrderPolicyVersion","FIRST_CONFIRMED_PAID_ORDER_V1"));
        jwt("evidence-reader","trade.admin",tenant); assertEquals(403,request("POST",path,"evidence-reader",body).statusCode());
        jwt("evidence-reader","trade.evidence.read",tenant); var response=request("POST",path,"evidence-reader",body);
        assertEquals(200,response.statusCode(),response.body()); var result=json.readTree(response.body());
        assertEquals("READY",result.path("state").asText()); assertEquals(order.orderId(),result.path("evidence").path("orderId").asText());
        assertEquals(clock.instant(),Instant.parse(result.path("firstConfirmedAt").asText()));
        var batch=request("POST","/internal/v1/order-referral-evidence:query","evidence-reader",json.writeValueAsString(Map.of("orderIds",List.of(order.orderId()))));
        assertTrue(json.readTree(batch.body()).isArray());
        jwt("evidence-reader","trade.evidence.read",tenant+10000); var foreign=request("POST",path,"evidence-reader",body);
        assertEquals(200,foreign.statusCode()); assertEquals("NOT_FOUND",json.readTree(foreign.body()).path("state").asText());
    }

    @Test void evidenceHttpRequiresDedicatedScopeAndTrustedIdentityAndValidTenant() throws Exception {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"evidence-http",1);
        String path="/internal/v1/orders/"+order.orderId()+"/referral-evidence"; long tenant=f.actor().tenantId();
        assertEquals(401,request("GET",path,null,null).statusCode());
        jwt("evidence-reader","",tenant); assertEquals(403,request("GET",path,"evidence-reader",null).statusCode());
        jwt("evidence-reader","trade.admin",tenant); assertEquals(403,request("GET",path,"evidence-reader",null).statusCode());
        jwt("not-trusted","trade.evidence.read",tenant);
        var untrusted=request("GET",path,"not-trusted",null); assertEquals(403,untrusted.statusCode()); assertTrue(untrusted.body().contains("EVIDENCE_UNTRUSTED"));
        when(decoder.decode("evidence-reader")).thenReturn(Jwt.withTokenValue("evidence-reader").header("alg","RS256").issuer("https://identity.test")
            .subject("evidence-reader").claim("tenant_id","not-a-tenant").claim("scope","trade.evidence.read")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());
        assertEquals(403,request("GET",path,"evidence-reader",null).statusCode());
        jwt("evidence-reader","trade.evidence.read",tenant);
        var found=request("GET",path,"evidence-reader",null); assertEquals(200,found.statusCode(),found.body());
        var json=tools.jackson.databind.json.JsonMapper.builder().build(); var data=json.readTree(found.body());
        assertEquals(100,data.path("paidAmount").asLong()); assertTrue(data.path("isFirstConfirmedPaidOrder").asBoolean());
        assertFalse(found.body().contains("providerTransactionNo")); assertFalse(found.body().contains("credential"));
        jwt("evidence-reader","trade.evidence.read",tenant+10000); assertEquals(404,request("GET",path,"evidence-reader",null).statusCode());
    }

    @Test void evidenceHttpNativeBatchAndEligibilityContractsRejectUnsupportedShapes() throws Exception {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"evidence-batch-http",1); long tenant=f.actor().tenantId();
        jwt("evidence-reader","trade.evidence.read",tenant); var json=tools.jackson.databind.json.JsonMapper.builder().build();
        String batch="/internal/v1/order-referral-evidence:query";
        var response=request("POST",batch,"evidence-reader",json.writeValueAsString(Map.of("orderIds",List.of(order.orderId(),"9223372036854775807"))));
        assertEquals(200,response.statusCode(),response.body()); var rows=json.readTree(response.body());
        assertEquals("FOUND",rows.get(0).path("status").asText()); assertEquals("NOT_FOUND",rows.get(1).path("status").asText()); assertTrue(rows.get(1).path("evidence").isNull());
        assertEquals(400,request("POST",batch,"evidence-reader",json.writeValueAsString(Map.of("orderIds",Collections.nCopies(101,order.orderId())))).statusCode());
        assertEquals(400,request("POST",batch,"evidence-reader","{\"orderIds\":[null]}").statusCode());
        assertEquals(400,request("POST",batch,"evidence-reader","{\"orderIds\":[\"9223372036854775808\"]}").statusCode());
        assertEquals(400,request("POST",batch,"evidence-reader","{\"canonicalSubject\":\"unknown\",\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-07T00:00:00Z\",\"firstOrderPolicyVersion\":\"v1\"}").statusCode());
        String customer="/internal/v1/customer-eligibility:query";
        var input=Map.of("memberId",members.me(f.actor()).memberId(),"policyVersion","NEVER_CONFIRMED_PAID_V1");
        var eligibilityResponse=request("POST",customer,"evidence-reader",json.writeValueAsString(input));
        assertEquals(200,eligibilityResponse.statusCode(),eligibilityResponse.body()); var data=json.readTree(eligibilityResponse.body());
        assertEquals("READY",data.path("status").asText()); assertFalse(data.path("evidence").path("eligible").asBoolean()); assertTrue(data.path("evidence").path("everPaid").asBoolean());
        assertEquals(400,request("POST",customer,"evidence-reader","{\"canonicalSubject\":\"unknown\",\"policyCode\":\"NEVER\",\"policyVersion\":\"v1\"}").statusCode());
        assertEquals(400,request("POST",customer,"evidence-reader",json.writeValueAsString(Map.of("memberId",members.me(f.actor()).memberId(),"policyVersion","NEVER_CONFIRMED_PAID_V1","tenantId",tenant+1))).statusCode());
    }

    @Autowired CustomerEligibilityService eligibility;
    @Autowired com.lrj.trade.member.CustomerFactPort customerFactPort;

    @Test void eligibilitySeparatesVersionedPoliciesAndRegistrationTimeBoundaries() {
        Fixture f=orderFixture(1); long tenant=f.actor().tenantId(),member=Long.parseLong(members.me(f.actor()).memberId());
        Instant registered=clock.instant(); assertEquals(registered,Instant.parse(members.me(f.actor()).registeredAt()));
        var never=eligibility.query(tenant,member,"NEVER_CONFIRMED_PAID_V1",null,registered).evidence();
        assertTrue(never.eligible()); assertFalse(never.everPaid()); assertFalse(never.everPaidAtEvaluation()); assertNull(never.firstPaidOrderNo());
        assertTrue(eligibility.query(tenant,member,"REGISTERED_DURING_CAMPAIGN_V1",registered,registered).evidence().eligible());
        assertFalse(eligibility.query(tenant,member,"REGISTERED_DURING_CAMPAIGN_V1",registered.plusMillis(1),registered).evidence().eligible());
        var before=eligibility.query(tenant,member,"NEVER_CONFIRMED_PAID_V1",null,registered.minusMillis(1)).evidence();
        assertFalse(before.eligible()); assertEquals(List.of("NOT_REGISTERED_AT_EVALUATION"),before.reasonCodes());
        assertCode("INVALID_ARGUMENT",()->eligibility.query(tenant,member,"UNVERSIONED",null,registered));
        assertCode("INVALID_ARGUMENT",()->eligibility.query(tenant,member,"REGISTERED_DURING_CAMPAIGN_V1",null,registered));
        assertCode("INVALID_ARGUMENT",()->eligibility.query(tenant,member,"NEVER_CONFIRMED_PAID_V1",null,registered.plusMillis(1)));
    }

    @Test void eligibilityRefundNeverMakesOldCustomerNewAndHistoricalProjectionIsExplicit() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(),member=Long.parseLong(members.me(f.actor()).memberId());
        Instant registered=clock.instant(),paidAt=registered.plusSeconds(10); when(clock.instant()).thenReturn(paidAt);
        var order=paidRefundOrder(f,"eligibility-paid",1);
        var earlier=eligibility.query(tenant,member,"NEVER_CONFIRMED_PAID_V1",null,paidAt.minusMillis(1)).evidence();
        assertTrue(earlier.eligible()); assertTrue(earlier.everPaid()); assertFalse(earlier.everPaidAtEvaluation());
        assertEquals(order.orderNo(),earlier.firstPaidOrderNo()); assertEquals(paidAt.minusMillis(1),earlier.evaluatedAt());
        var now=eligibility.query(tenant,member,"NEVER_CONFIRMED_PAID_V1",null,paidAt).evidence();
        assertFalse(now.eligible()); assertTrue(now.everPaidAtEvaluation()); assertEquals(List.of("ALREADY_CONFIRMED_PAID"),now.reasonCodes());
        assertTrue(eligibility.query(tenant,member,"REGISTERED_DURING_CAMPAIGN_V1",registered,paidAt).evidence().eligible());
        var refund=requestRefund(f,order,"all",100); approveRefund(f,refund,true,1); prepareRefund(f,refund); completeRefund(f,refund,"eligibility-refund");
        assertEquals(now,eligibility.query(tenant,member,"NEVER_CONFIRMED_PAID_V1",null,paidAt).evidence());
        assertEquals(earlier,eligibility.query(tenant,member,"NEVER_CONFIRMED_PAID_V1",null,paidAt.minusMillis(1)).evidence());
    }

    @Test void eligibilityDoesNotTreatMissingOrOtherTenantMembersAsNew() {
        Fixture f=orderFixture(1); long member=Long.parseLong(members.me(f.actor()).memberId());
        var missing=eligibility.query(f.actor().tenantId(),member+10000,"NEVER_CONFIRMED_PAID_V1",null,null);
        assertEquals("NOT_FOUND",missing.status()); assertNull(missing.evidence());
        var foreign=eligibility.query(f.actor().tenantId()+10000,member,"NEVER_CONFIRMED_PAID_V1",null,null);
        assertEquals("NOT_FOUND",foreign.status()); assertNull(foreign.evidence());
    }

    @Test void eligibilitySnapshotIsPointInTimeEvidenceNotReservationAgainstConcurrentPayment() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(),member=Long.parseLong(members.me(f.actor()).memberId());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var service=new CustomerEligibilityService((t,m)->{
                var facts=customerFactPort.read(t,m);
                try { pool.submit(()->paidRefundOrder(f,"paid-after-read",1)).get(10,TimeUnit.SECONDS); }
                catch(Exception e) { throw new IllegalStateException(e); }
                return facts;
            },orderEvidencePort,clock,transactions);
            var old=service.query(tenant,member,"NEVER_CONFIRMED_PAID_V1",null,null).evidence();
            assertTrue(old.eligible()); assertFalse(old.everPaid()); assertEquals(0,old.factVersion());
        }
        var current=eligibility.query(tenant,member,"NEVER_CONFIRMED_PAID_V1",null,null).evidence();
        assertFalse(current.eligible()); assertTrue(current.everPaid()); assertEquals(1,current.factVersion());
    }

    @Test void eligibilityDefaultCurrentSnapshotIncludesPaymentCommittedBeforeItsFirstRead() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(),member=Long.parseLong(members.me(f.actor()).memberId());
        Instant before=clock.instant();
        try(var pool=Executors.newSingleThreadExecutor()) {
            var service=new CustomerEligibilityService((t,m)->{
                when(clock.instant()).thenReturn(before.plusSeconds(1));
                try { pool.submit(()->paidRefundOrder(f,"paid-before-read",1)).get(10,TimeUnit.SECONDS); }
                catch(Exception e) { throw new IllegalStateException(e); }
                return customerFactPort.read(t,m);
            },orderEvidencePort,clock,transactions);
            var current=service.query(tenant,member,"NEVER_CONFIRMED_PAID_V1",null,null).evidence();
            assertTrue(current.everPaid()); assertTrue(current.everPaidAtEvaluation()); assertFalse(current.eligible());
            assertEquals(before.plusSeconds(1),current.evaluatedAt());
        }
    }

    @Autowired OrderReferralEvidenceService referralEvidence;
    @Autowired com.lrj.trade.order.OrderEvidencePort orderEvidencePort;
    @Autowired com.lrj.trade.payment.PaymentEvidencePort paymentEvidencePort;
    @Autowired com.lrj.trade.member.MemberPaidEvidencePort memberPaidEvidencePort;

    @Test void evidenceTracksNormalAmountsPendingReleasesAndIrreversibleFirstOrder() {
        Fixture f=paymentFixture(5); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"evidence-first",3);
        var second=paidRefundOrder(f,"evidence-second",1); var unpaid=factOrder(f,"unpaid");
        var paid=orderEvidence(f,order); assertEquals(300,paid.paidAmount()); assertEquals(300,paid.netPaidAmount());
        assertTrue(paid.isFirstConfirmedPaidOrder()); assertFalse(orderEvidence(f,second).isFirstConfirmedPaidOrder());
        assertEquals(0,orderEvidence(f,unpaid).paidAmount()); assertNull(orderEvidence(f,unpaid).paidAt());
        assertNull(paid.completedAt()); fulfillment.deliver(f.actor(),"delivery",order.orderNo(),"proof",clock.instant());
        fulfillment.complete(f.actor(),"complete",order.orderNo(),"proof",clock.instant());
        var completed=orderEvidence(f,order); assertEquals(clock.instant(),completed.completedAt()); assertTrue(completed.evidenceVersion()>paid.evidenceVersion());
        var first=requestRefund(f,order,"pending",100); var pending=orderEvidence(f,order);
        assertEquals(100,pending.pendingRefundAmount()); assertEquals(300,pending.netPaidAmount()); assertEquals(200,pending.conservativeEligibleAmount());
        assertTrue(pending.evidenceVersion()>completed.evidenceVersion());
        approveRefund(f,first,true,1); prepareRefund(f,first); completeRefund(f,first,"first-provider");
        var partial=orderEvidence(f,order); assertEquals(100,partial.refundedAmount()); assertEquals(0,partial.pendingRefundAmount()); assertEquals(200,partial.netPaidAmount());
        assertTrue(partial.evidenceVersion()>pending.evidenceVersion());
        var released=requestRefund(f,order,"release",50); var reserved=orderEvidence(f,order);
        assertEquals(150,reserved.conservativeEligibleAmount()); approveRefund(f,released,false,1);
        var afterRelease=orderEvidence(f,order); assertEquals(200,afterRelease.conservativeEligibleAmount()); assertTrue(afterRelease.evidenceVersion()>reserved.evidenceVersion());
        var rest=requestRefund(f,order,"rest",200); approveRefund(f,rest,true,1); prepareRefund(f,rest); completeRefund(f,rest,"rest-provider");
        var full=orderEvidence(f,order); assertEquals(300,full.paidAmount()); assertEquals(300,full.refundedAmount()); assertEquals(0,full.netPaidAmount());
        assertTrue(full.isFirstConfirmedPaidOrder()); assertEquals(paid.paidAt(),full.paidAt()); assertEquals(completed.completedAt(),full.completedAt());
        assertEquals("FIRST_CONFIRMED_PAID_ORDER_V1",full.firstOrderPolicy()); assertTrue(members.me(f.actor()).everPaid());
    }

    @Test void evidenceExcludesAnomalousReceiptsAndCompensationVersions() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"evidence-anomaly",1);
        var before=orderEvidence(f,order);
        String paymentNo=jdbc.queryForObject("SELECT payment_no FROM tc_payment_intent WHERE tenant_id=? AND order_id=?",String.class,tenant,Long.parseLong(order.orderId()));
        var payment=payments.read(f.actor(),paymentNo); var excess=receive(f,payment,"excess-evidence",100,"CNY");
        var compensation=refunds.requestCompensation(tenant,excess.receiptNo());
        var during=orderEvidence(f,order); assertEquals(before,during);
        approveRefund(f,compensation,true,1); prepareRefund(f,compensation); completeRefund(f,compensation,"compensation-provider");
        assertEquals(before,orderEvidence(f,order));
    }

    @Test void evidenceBatchIsBoundedPreservesOrderAndUsesThreeSetQueries() {
        Fixture f=paymentFixture(2); var order=paidRefundOrder(f,"batch",1); Fixture other=paymentFixture(1); var foreign=paidRefundOrder(other,"foreign",1);
        long own=Long.parseLong(order.orderId()),foreignId=Long.parseLong(foreign.orderId()); var calls=new AtomicLong();
        var service=new OrderReferralEvidenceService((t,ids)->{ calls.incrementAndGet(); assertEquals(2,ids.size()); return orderEvidencePort.read(t,ids); },
            (t,ids)->{ calls.incrementAndGet(); return paymentEvidencePort.read(t,ids); },
            (t,ids)->{ calls.incrementAndGet(); return memberPaidEvidencePort.read(t,ids); },transactions,clock,customerFactPort);
        var ids=new ArrayList<Long>(); for(int i=0;i<100;i++) ids.add(i%2==0?own:foreignId);
        var rows=service.query(f.actor().tenantId(),ids); assertEquals(100,rows.size()); assertEquals(3,calls.get());
        for(int i=0;i<100;i++) {
            assertEquals(Long.toString(ids.get(i)),rows.get(i).orderId()); assertEquals(i%2==0?"FOUND":"NOT_FOUND",rows.get(i).status());
            if(i%2==1) assertNull(rows.get(i).evidence());
        }
        assertCode("INVALID_ARGUMENT",()->referralEvidence.query(f.actor().tenantId(),Collections.nCopies(101,own)));
        assertCode("INVALID_ARGUMENT",()->referralEvidence.query(f.actor().tenantId(),List.of()));
        assertCode("INVALID_ARGUMENT",()->referralEvidence.query(f.actor().tenantId(),Arrays.asList(own,null)));
    }

    @Test void evidenceSnapshotCannotMixNewRefundFundsWithOldOrderVersionEvenInsideReadCommitted() {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"snapshot",1); var before=orderEvidence(f,order);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var service=new OrderReferralEvidenceService((t,ids)->{
                assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ,org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                var headers=orderEvidencePort.read(t,ids);
                try { pool.submit(()->requestRefund(f,order,"concurrent-refund",100)).get(10,TimeUnit.SECONDS); }
                catch(Exception e) { throw new IllegalStateException(e); }
                return headers;
            },paymentEvidencePort,memberPaidEvidencePort,transactions,clock,customerFactPort);
            var outer=new TransactionTemplate(transactions); outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            var old=outer.execute(tx->service.query(f.actor().tenantId(),List.of(Long.parseLong(order.orderId()))).getFirst().evidence());
            assertEquals(before,old);
        }
        var latest=orderEvidence(f,order); assertEquals(100,latest.pendingRefundAmount()); assertEquals(0,latest.conservativeEligibleAmount());
        assertTrue(latest.evidenceVersion()>before.evidenceVersion());
    }

    @Test void evidenceIndependentSnapshotDoesNotPublishOuterUncommittedRefund() {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"uncommitted",1); var before=orderEvidence(f,order);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            requestRefund(f,order,"not-committed",100);
            assertEquals(before,orderEvidence(f,order));
            tx.setRollbackOnly();
        });
        assertEquals(before,orderEvidence(f,order));
    }

    @Test void evidenceFailsClosedWhenUnbackedConsumptionFactExists() {
        Fixture f=orderFixture(1); var order=factOrder(f,"inconsistent"); confirmFact(f,order);
        assertCode("EVIDENCE_UNAVAILABLE",()->orderEvidence(f,order));
    }

    private OrderReferralEvidenceService.Evidence orderEvidence(Fixture f,com.lrj.trade.order.model.OrderView order) {
        return referralEvidence.query(f.actor().tenantId(),List.of(Long.parseLong(order.orderId()))).getFirst().evidence();
    }

    @Autowired com.lrj.trade.order.OrderReturnService returns;
    @Autowired com.lrj.trade.inventory.InventoryReturnPort inventoryReturns;

    @Test void returnReceiptConcurrentAndPermanentReplayRestocksOnlyOnce() throws Exception {
        Fixture f=paymentFixture(5); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"return",3);
        var source=new Actor(tenant,"https://warehouse.test","inspector");
        fulfillment.deliver(source,"delivered",order.orderNo(),"proof",clock.instant());
        long item=Long.parseLong(order.items().getFirst().itemId());
        var ids=parallel(20,()->returns.inspect(source,"return",order.orderNo(),item,f.warehouse(),2,1,"inspection"));
        assertEquals(1,ids.stream().distinct().count());
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=? AND operation='order.return.inspect'",tenant);
        assertCode("RETURN_CONFLICT",()->returns.inspect(source,"return",order.orderNo(),item,f.warehouse(),2,2,"inspection"));
        for(int i=0;i<100;i++) assertEquals(ids.getFirst(),returns.inspect(source,"return",order.orderNo(),item,f.warehouse(),2,1,"inspection"));
        assertEquals(3,balance(f).available()); assertEquals(2,balance(f).sold()); assertEquals(5,balance(f).stockTotal());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_inventory_ledger WHERE tenant_id=? AND operation_type='RETURN'",tenant));
        assertEquals(2,count("SELECT returned_quantity FROM tc_order_item WHERE tenant_id=? AND id=?",tenant,item));
        assertEquals(1,count("SELECT restocked_quantity FROM tc_inventory_reservation WHERE tenant_id=?",tenant));
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT status FROM tc_inventory_reservation WHERE tenant_id=?",String.class,tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='order.return.inspect'",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='order.return.inspected.internal.v0'",tenant));
        assertTrue(members.me(f.actor()).everPaid()); assertEquals(order.paidAt(),orders.read(f.actor(),order.orderNo()).paidAt());
    }

    @Test void returnReceiptDifferentEventsCannotExceedActualDeliveredQuantity() throws Exception {
        Fixture f=paymentFixture(5); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"return-budget",3);
        var source=f.actor(); fulfillment.deliver(source,"delivered",order.orderNo(),"proof",clock.instant());
        long item=Long.parseLong(order.items().getFirst().itemId()); var index=new AtomicLong();
        var outcomes=parallel(10,()->{
            try { returns.inspect(source,"return-"+index.incrementAndGet(),order.orderNo(),item,f.warehouse(),1,1,"inspection"); return "OK"; }
            catch(TradeException e) { assertEquals("RETURN_INVALID",e.code()); return "DENIED"; }
        });
        assertEquals(3,outcomes.stream().filter("OK"::equals).count());
        assertEquals(3,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",tenant));
        assertEquals(5,balance(f).available()); assertEquals(0,balance(f).sold());
    }

    @Test void returnReceiptCrossWarehouseConservesBothBalancesAndRejectsInvalidInputs() {
        Fixture f=paymentFixture(5); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"cross",3);
        long item=Long.parseLong(order.items().getFirst().itemId());
        long destination=Long.parseLong(inventory.warehouse(f.actor(),"destination","destination","接收仓"));
        assertCode("RETURN_INVALID",()->returns.inspect(f.actor(),"early",order.orderNo(),item,destination,1,1,"proof"));
        fulfillment.deliver(f.actor(),"delivered",order.orderNo(),"proof",clock.instant());
        assertCode("NOT_FOUND",()->returns.inspect(new Actor(tenant+10000,"issuer","other"),"foreign",order.orderNo(),item,destination,1,1,"proof"));
        assertCode("RETURN_INVALID",()->returns.inspect(f.actor(),"wrong-item",order.orderNo(),item+10000,destination,1,1,"proof"));
        assertCode("NOT_FOUND",()->returns.inspect(f.actor(),"wrong-warehouse",order.orderNo(),item,destination+10000,1,0,"proof"));
        assertCode("INVALID_ARGUMENT",()->returns.inspect(f.actor(),"invalid",order.orderNo(),item,destination,1,2,"proof"));
        String number=returns.inspect(f.actor(),"cross",order.orderNo(),item,destination,2,2,"proof");
        var balances=inventory.balances(tenant,0,20);
        var origin=balances.stream().filter(b->b.warehouseId().equals(Long.toString(f.warehouse()))).findFirst().orElseThrow();
        var target=balances.stream().filter(b->b.warehouseId().equals(Long.toString(destination))).findFirst().orElseThrow();
        assertEquals(3,origin.stockTotal()); assertEquals(2,origin.available()); assertEquals(1,origin.sold());
        assertEquals(2,target.stockTotal()); assertEquals(2,target.available()); assertEquals(0,target.sold());
        assertEquals(0,origin.reserved()+target.reserved());
        assertEquals(2,count("SELECT COUNT(*) FROM tc_inventory_ledger WHERE tenant_id=? AND operation_ref IN (?,?)",tenant,number+":OUT",number+":IN"));
        assertEquals(0,count("SELECT SUM(total_delta) FROM tc_inventory_ledger WHERE tenant_id=? AND operation_type='RETURN'",tenant));
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=? AND operation='order.return.inspect'",tenant);
        assertEquals(number,returns.inspect(f.actor(),"cross",order.orderNo(),item,destination,2,2,"proof"));
        assertEquals(balances,inventory.balances(tenant,0,20));
        returns.inspect(f.actor(),"damaged",order.orderNo(),item,destination,1,0,"damaged-proof");
        assertEquals(balances,inventory.balances(tenant,0,20));
        assertCode("RETURN_INVALID",()->returns.inspect(f.actor(),"too-many",order.orderNo(),item,destination,1,0,"proof"));
    }

    @Test void returnReceiptOuterRollbackUndoesFactsBudgetsBothWarehousesAndEvents() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"rollback-return",1);
        fulfillment.deliver(f.actor(),"delivered",order.orderNo(),"proof",clock.instant());
        long item=Long.parseLong(order.items().getFirst().itemId());
        long destination=Long.parseLong(inventory.warehouse(f.actor(),"destination","destination","接收仓"));
        var stock=inventory.balances(tenant,0,20);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            returns.inspect(f.actor(),"return",order.orderNo(),item,destination,1,1,"proof"); throw new IllegalStateException("rollback");
        }));
        assertEquals(stock,inventory.balances(tenant,0,20));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_inventory_return_operation WHERE tenant_id=?",tenant));
        assertEquals(0,count("SELECT returned_quantity FROM tc_order_item WHERE tenant_id=? AND id=?",tenant,item));
        assertEquals(0,count("SELECT restocked_quantity FROM tc_inventory_reservation WHERE tenant_id=?",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_inventory_ledger WHERE tenant_id=? AND operation_type='RETURN'",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type IN ('order.return.inspected.internal.v0','inventory.returned.internal.v0')",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='order.return.inspect'",tenant));
        returns.inspect(f.actor(),"return",order.orderNo(),item,destination,1,1,"proof");
        assertEquals(1,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",tenant));
    }

    @Test void returnInventoryPortGuardsOriginalReservationAndPermanentOperationIndependently() {
        Fixture f=paymentFixture(3); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"port",1);
        var second=paidRefundOrder(f,"port-other",1);
        String reservation=order.orderNo()+":"+f.sku(); var tx=new TransactionTemplate(transactions);
        assertCode("RETURN_CONFLICT",()->tx.execute(s->inventoryReturns.restock(f.actor(),"bad-sku",reservation,f.sku()+1,1,f.warehouse(),1)));
        assertCode("RETURN_CONFLICT",()->tx.execute(s->inventoryReturns.restock(f.actor(),"bad-original",reservation,f.sku(),2,f.warehouse(),1)));
        assertEquals("operation",tx.execute(s->inventoryReturns.restock(f.actor(),"operation",reservation,f.sku(),1,f.warehouse(),1)));
        var stock=balance(f);
        for(int i=0;i<100;i++) assertEquals("operation",tx.execute(s->inventoryReturns.restock(f.actor(),"operation",reservation,f.sku(),1,f.warehouse(),1)));
        assertCode("RETURN_CONFLICT",()->tx.execute(s->inventoryReturns.restock(f.actor(),"operation",second.orderNo()+":"+f.sku(),f.sku(),1,f.warehouse(),1)));
        assertCode("RETURN_CONFLICT",()->tx.execute(s->inventoryReturns.restock(f.actor(),"another",reservation,f.sku(),1,f.warehouse(),1)));
        assertEquals(stock,balance(f)); assertEquals(1,balance(f).sold());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_inventory_return_operation WHERE tenant_id=?",tenant));
    }

    @Test void returnReceiptCrossOrderPermanentEventCannotBeRebound() {
        Fixture f=paymentFixture(3); long tenant=f.actor().tenantId(); var first=paidRefundOrder(f,"first",1); var second=paidRefundOrder(f,"second",1);
        fulfillment.deliver(f.actor(),"first",first.orderNo(),"proof",clock.instant());
        fulfillment.deliver(f.actor(),"second",second.orderNo(),"proof",clock.instant());
        returns.inspect(f.actor(),"stable",first.orderNo(),Long.parseLong(first.items().getFirst().itemId()),f.warehouse(),1,1,"proof");
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=? AND operation='order.return.inspect'",tenant);
        assertCode("RETURN_CONFLICT",()->returns.inspect(f.actor(),"stable",second.orderNo(),Long.parseLong(second.items().getFirst().itemId()),f.warehouse(),1,1,"proof"));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",tenant));
        assertEquals(1,balance(f).sold());
    }

    @Test void returnReceiptOppositeWarehouseTransfersSerializeWithoutDeadlock() throws Exception {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId();
        long otherWarehouse=Long.parseLong(inventory.warehouse(f.actor(),"other","other","第二出库仓"));
        inventory.adjust(f.actor(),"other-stock",otherWarehouse,f.sku(),2,"初始库存");
        Fixture other=new Fixture(f.actor(),otherWarehouse,f.sku());
        var one=paidRefundOrder(f,"one",2); var two=paidRefundOrder(other,"two",2);
        fulfillment.deliver(f.actor(),"one",one.orderNo(),"proof",clock.instant());
        fulfillment.deliver(f.actor(),"two",two.orderNo(),"proof",clock.instant());
        var barrier=new CyclicBarrier(2); var index=new AtomicLong();
        var outcomes=parallel(2,()->{
            boolean first=index.incrementAndGet()==1; var order=first?one:two;
            return new TransactionTemplate(transactions).execute(tx->{
                jdbc.queryForObject("SELECT id FROM tc_order WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,tenant,Long.parseLong(order.orderId()));
                try { barrier.await(10,TimeUnit.SECONDS); } catch(Exception e) { throw new IllegalStateException(e); }
                return returns.inspect(f.actor(),first?"return-one":"return-two",order.orderNo(),Long.parseLong(order.items().getFirst().itemId()),first?otherWarehouse:f.warehouse(),2,2,"proof");
            });
        });
        assertEquals(2,new HashSet<>(outcomes).size());
        for(var b:inventory.balances(tenant,0,20)) {
            assertEquals(2,b.stockTotal()); assertEquals(2,b.available()); assertEquals(0,b.sold()); assertEquals(0,b.reserved());
        }
        assertEquals(4,count("SELECT COUNT(*) FROM tc_inventory_ledger WHERE tenant_id=? AND operation_type='RETURN'",tenant));
        assertEquals(2,count("SELECT COUNT(*) FROM tc_inventory_return_operation WHERE tenant_id=?",tenant));
    }

    @Test void returnReceiptRefundOutcomesNeverCreateInspectionAndDoNotBlockActualReturns() {
        Fixture f=paymentFixture(3); long tenant=f.actor().tenantId();
        var rejectedOrder=paidRefundOrder(f,"rejected",1); var unknownOrder=paidRefundOrder(f,"unknown",1); var succeededOrder=paidRefundOrder(f,"succeeded",1);
        for(var o:List.of(rejectedOrder,unknownOrder,succeededOrder)) fulfillment.deliver(f.actor(),o.orderNo(),o.orderNo(),"proof",clock.instant());
        var rejected=requestRefund(f,rejectedOrder,"reject",100); approveRefund(f,rejected,false,1);
        var unknown=requestRefund(f,unknownOrder,"unknown",100); approveRefund(f,unknown,true,1);
        assertTrue(refundRecoveryWith((t,plan)->com.lrj.trade.refund.RefundChannelPort.Outcome.UNKNOWN).runBatch(tenant,"unknown-return",10).contains(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.RETRY));
        assertEquals("SUBMITTED",refunds.read(f.actor(),unknown.refundNo()).status());
        var succeeded=requestRefund(f,succeededOrder,"succeeded",100); approveRefund(f,succeeded,true,1); prepareRefund(f,succeeded); completeRefund(f,succeeded,"provider");
        assertEquals(0,count("SELECT COUNT(*) FROM tc_return_receipt WHERE tenant_id=?",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_inventory_return_operation WHERE tenant_id=?",tenant));
        assertEquals(3,balance(f).sold()); assertEquals(0,balance(f).available());
        for(var o:List.of(rejectedOrder,unknownOrder,succeededOrder)) returns.inspect(f.actor(),o.orderNo(),o.orderNo(),Long.parseLong(o.items().getFirst().itemId()),f.warehouse(),1,1,"proof");
        assertEquals(3,balance(f).available()); assertEquals(0,balance(f).sold()); assertTrue(members.me(f.actor()).everPaid());
    }

    @Autowired com.lrj.trade.order.OrderFulfillmentService fulfillment;

    @Test void fulfillmentDeliveryAndCompletionAreDistinctPermanentFacts() throws Exception {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId();
        var order=paidRefundOrder(f,"fulfillment",1); var before=orders.read(f.actor(),order.orderNo()); var stock=balance(f);
        var source=new Actor(tenant,"https://warehouse.test","delivery-service"); Instant delivered=clock.instant();
        var receipts=parallel(20,()->fulfillment.deliver(source,"delivery-one",order.orderNo(),"proof:whole-order",delivered));
        assertEquals(1,receipts.stream().distinct().count());
        var inProgress=orders.read(f.actor(),order.orderNo()); assertEquals("FULFILLING",inProgress.status());
        assertNull(inProgress.completedAt()); assertEquals(before.paidAt(),inProgress.paidAt()); assertEquals(stock,balance(f));
        Instant completed=delivered.plusSeconds(30); when(clock.instant()).thenReturn(completed);
        for(int i=0;i<2;i++) assertEquals(receipts.getFirst(),fulfillment.complete(source,"complete-one",order.orderNo(),"proof:completed",completed));
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=? AND operation IN ('order.delivered','order.completed')",tenant);
        for(int i=0;i<100;i++) {
            assertEquals(receipts.getFirst(),fulfillment.deliver(source,"delivery-one",order.orderNo(),"proof:whole-order",delivered));
            assertEquals(receipts.getFirst(),fulfillment.complete(source,"complete-one",order.orderNo(),"proof:completed",completed));
        }
        var done=orders.read(f.actor(),order.orderNo()); assertEquals("COMPLETED",done.status()); assertEquals(completed,done.completedAt());
        assertEquals(before.paidAt(),done.paidAt()); assertEquals(stock,balance(f)); assertTrue(members.me(f.actor()).everPaid());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_order_fulfillment WHERE tenant_id=?",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='order.delivered.internal.v0'",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='order.completed.internal.v0'",tenant));
        var refund=requestRefund(f,done,"after-completed",100); approveRefund(f,refund,true,1); prepareRefund(f,refund); completeRefund(f,refund,"refund-delivered");
        assertEquals("COMPLETED",orders.read(f.actor(),order.orderNo()).status()); assertEquals(completed,orders.read(f.actor(),order.orderNo()).completedAt());
        assertEquals(stock,balance(f)); assertTrue(members.me(f.actor()).everPaid());
    }

    @Test void fulfillmentRejectsUnpaidForeignConflictingAndOutOfOrderFacts() {
        Fixture f=paymentFixture(4); long tenant=f.actor().tenantId(); var source=new Actor(tenant,"https://warehouse.test","delivery-service");
        var unpaid=factOrder(f,"unpaid-fulfillment"); var at=clock.instant();
        assertCode("FULFILLMENT_INVALID",()->fulfillment.deliver(source,"unpaid",unpaid.orderNo(),"proof",at));
        var first=paidRefundOrder(f,"first-delivery",1); var second=paidRefundOrder(f,"second-delivery",1);
        assertCode("FULFILLMENT_INVALID",()->fulfillment.complete(source,"too-early",first.orderNo(),"proof",at));
        assertCode("NOT_FOUND",()->fulfillment.deliver(new Actor(tenant+10000,source.issuer(),source.subject()),"foreign",first.orderNo(),"proof",at));
        assertCode("FULFILLMENT_INVALID",()->fulfillment.deliver(source,"future",first.orderNo(),"proof",at.plusSeconds(1)));
        assertCode("FULFILLMENT_INVALID",()->fulfillment.deliver(source,"before-created",first.orderNo(),"proof",at.minusMillis(1)));
        fulfillment.deliver(source,"stable",first.orderNo(),"proof",at);
        assertCode("FULFILLMENT_INVALID",()->fulfillment.complete(source,"future-completion",first.orderNo(),"proof",at.plusSeconds(1)));
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=? AND operation='order.delivered'",tenant);
        assertCode("FULFILLMENT_CONFLICT",()->fulfillment.deliver(source,"stable",first.orderNo(),"changed-proof",at));
        assertCode("FULFILLMENT_CONFLICT",()->fulfillment.deliver(source,"stable",second.orderNo(),"proof",at));
        assertCode("FULFILLMENT_INVALID",()->fulfillment.complete(source,"before-delivery",first.orderNo(),"proof",at.minusSeconds(1)));
        fulfillment.complete(source,"complete",first.orderNo(),"proof",at);
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=? AND operation='order.completed'",tenant);
        assertCode("FULFILLMENT_CONFLICT",()->fulfillment.complete(source,"complete",first.orderNo(),"changed-proof",at));
        assertEquals("PAID",orders.read(f.actor(),second.orderNo()).status());
    }

    @Test void fulfillmentConcurrentCompletionAndCrossOrderReplayCannotOverwriteEvidence() throws Exception {
        Fixture f=paymentFixture(3); long tenant=f.actor().tenantId(); var source=new Actor(tenant,"https://warehouse.test","delivery-service");
        var first=paidRefundOrder(f,"concurrent-completion",1); var second=paidRefundOrder(f,"other-completion",1); Instant at=clock.instant();
        fulfillment.deliver(source,"delivered-first",first.orderNo(),"proof",at);
        fulfillment.deliver(source,"delivered-second",second.orderNo(),"proof",at);
        var index=new AtomicLong();
        var results=parallel(2,()->{
            String event="completion-"+index.incrementAndGet();
            try { fulfillment.complete(source,event,first.orderNo(),"proof",at); return event; }
            catch(TradeException conflict) { assertEquals("FULFILLMENT_CONFLICT",conflict.code()); return "CONFLICT"; }
        });
        assertEquals(1,results.stream().filter("CONFLICT"::equals).count());
        String winner=results.stream().filter(value->!value.equals("CONFLICT")).findFirst().orElseThrow();
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=? AND operation='order.completed'",tenant);
        assertCode("FULFILLMENT_CONFLICT",()->fulfillment.complete(source,winner,second.orderNo(),"proof",at));
        assertNull(orders.read(f.actor(),second.orderNo()).completedAt());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='order.completed'",tenant));
    }

    @Test void fulfillmentOuterRollbackLeavesNoDeliveryOrCompletion() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"fulfillment-rollback",1);
        var source=new Actor(tenant,"https://warehouse.test","delivery-service"); var at=clock.instant();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            fulfillment.deliver(source,"delivery",order.orderNo(),"proof",at); throw new IllegalStateException("rollback");
        }));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_order_fulfillment WHERE tenant_id=?",tenant));
        assertEquals("PAID",orders.read(f.actor(),order.orderNo()).status());
        fulfillment.deliver(source,"delivery",order.orderNo(),"proof",at);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            fulfillment.complete(source,"complete",order.orderNo(),"proof",at); throw new IllegalStateException("rollback");
        }));
        assertNull(orders.read(f.actor(),order.orderNo()).completedAt()); assertEquals("FULFILLING",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='order.completed.internal.v0'",tenant));
    }

    @Autowired com.lrj.trade.jobs.RefundCompensationWorker compensationWorker;
    @Autowired com.lrj.trade.payment.PaymentCompensationBackfillService compensationBackfill;

    @Test void compensationWorkerAtomicallyCreatesApprovalAndRejectsStaleLease() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(); var order=factOrder(f,"worker-compensation");
        var pay=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var source=receive(f,pay,"mismatch",99,"CNY");
        assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_COMPENSATE'",tenant));
        var old=jobLeases.claim(tenant,JobType.REFUND_COMPENSATE,"old",1,30000).getFirst(); expireLease(old);
        var current=jobLeases.claim(tenant,JobType.REFUND_COMPENSATE,"new",1,30000).getFirst();
        assertEquals(com.lrj.trade.jobs.RefundCompensationWorker.Outcome.LEASE_LOST,compensationWorker.processLease(old));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",tenant)); assertRefundAmounts(f,source,0,0);
        assertEquals(com.lrj.trade.jobs.RefundCompensationWorker.Outcome.DONE,compensationWorker.processLease(current));
        assertEquals("PENDING_APPROVAL",refunds.requestCompensation(tenant,source.receiptNo()).status());
        assertRefundAmounts(f,source,99,0);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_RECOVER'",tenant));
        assertTrue(compensationWorker.runBatch(tenant,"again",1).isEmpty());
    }

    @Test void compensationReceiptAndWorkerDatabaseFailuresRollBackAtomically() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(); var order=factOrder(f,"compensation-db");
        var pay=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            receive(f,pay,"mismatch",99,"CNY"); throw new IllegalStateException("receipt rollback");
        }));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_COMPENSATE'",tenant));
        var source=receive(f,pay,"mismatch",99,"CNY");
        var lease=jobLeases.claim(tenant,JobType.REFUND_COMPENSATE,"worker",1,30000).getFirst();
        var jobs=org.springframework.test.util.AopTestUtils.<com.lrj.trade.jobs.JobLeaseService>getUltimateTargetObject(jobLeases);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("database interrupted")).doCallRealMethod().when(jobs).complete(lease);
        assertEquals(com.lrj.trade.jobs.RefundCompensationWorker.Outcome.RETRY,compensationWorker.processLease(lease));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",tenant)); assertRefundAmounts(f,source,0,0);
        assertEquals("READY",jdbc.queryForObject("SELECT status FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_COMPENSATE'",String.class,tenant));
        assertEquals("DATABASE_RETRY",jdbc.queryForObject("SELECT last_error FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_COMPENSATE'",String.class,tenant));
    }

    @Test void compensationBackfillIsBoundedAndDoesNotRestartCompletedOrReviewTasks() {
        Fixture f=paymentFixture(3); long tenant=f.actor().tenantId(); var order=factOrder(f,"backfill-compensation");
        var pay=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var normal=receive(f,pay,"normal",100,"CNY"); var extra=receive(f,pay,"extra",100,"CNY");
        // 模拟V019迁移后仍存活旧写入者没有建任务；只删隔离测试任务，不动真实资金。
        jdbc.update("DELETE FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_COMPENSATE'",tenant);
        long cursor=compensationBackfill.enqueuePage(tenant,0,1); assertEquals(Long.parseLong(normal.receiptId()),cursor);
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_COMPENSATE'",tenant));
        assertEquals(Long.parseLong(extra.receiptId()),compensationBackfill.enqueuePage(tenant,cursor,1));
        assertEquals(List.of(com.lrj.trade.jobs.RefundCompensationWorker.Outcome.DONE),compensationWorker.runBatch(tenant,"worker",1));
        compensationBackfill.enqueuePage(tenant,0,100); assertTrue(compensationWorker.runBatch(tenant,"worker",1).isEmpty());
        var conflict=receive(f,pay,"partially-handled",100,"CNY"); reserveRefund(f,conflict,"manual-prior",10);
        assertEquals(List.of(com.lrj.trade.jobs.RefundCompensationWorker.Outcome.REVIEW),compensationWorker.runBatch(tenant,"worker",1));
        compensationBackfill.enqueuePage(tenant,0,100); assertTrue(compensationWorker.runBatch(tenant,"worker",1).isEmpty());
        assertRefundAmounts(f,conflict,10,0); assertEquals(1,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",tenant));
        assertCode("INVALID_ARGUMENT",()->compensationBackfill.enqueuePage(tenant,0,101));
    }

    @Test void compensationIsUniqueRequiresApprovalAndNeverChangesNormalConsumption() throws Exception {
        Fixture f=paymentFixture(3); long tenant=f.actor().tenantId(); var order=factOrder(f,"compensate");
        var pay=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var normal=receive(f,pay,"normal",100,"CNY"); var extra=receive(f,pay,"extra",100,"CNY");
        refundPolicies.configure(f.actor(),"policy","CNY",10000,0);
        var before=orders.read(f.actor(),order.orderNo()); var stock=balance(f);
        var result=parallel(20,()->refunds.requestCompensation(tenant,extra.receiptNo()));
        assertEquals(1,result.stream().map(com.lrj.trade.refund.model.RefundView::refundNo).distinct().count());
        var refund=result.getFirst(); assertEquals("PENDING_APPROVAL",refund.status()); assertNotNull(refund.approvalRef());
        assertEquals("EXCESS",refund.items().getFirst().componentType());
        assertCode("REFUND_COMPENSATION_INVALID",()->refunds.requestCompensation(tenant,normal.receiptNo()));
        assertCode("NOT_FOUND",()->refunds.requestCompensation(tenant+10000,extra.receiptNo()));
        assertEquals("WAIT_APPROVAL",prepareRefund(f,refund).action());
        approveRefund(f,refund,true,1); assertEquals("CREATE",prepareRefund(f,refund).action());
        for(int i=0;i<2;i++) completeRefund(f,refund,"provider-compensation");
        // 仅隔离测试库模拟短期幂等记录淘汰及系统身份升级，永久收款关联仍返回原申请。
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=? AND operation='refund.compensation'",tenant);
        jdbc.update("UPDATE tc_refund SET applicant_key=? WHERE tenant_id=? AND refund_no=?",Checks.hash("old-system"),tenant,refund.refundNo());
        for(int i=0;i<100;i++) assertEquals(refund.refundNo(),refunds.requestCompensation(tenant,extra.receiptNo()).refundNo());
        assertEquals(before,orders.read(f.actor(),order.orderNo())); assertEquals(stock,balance(f));
        assertTrue(members.me(f.actor()).everPaid());
        assertRefundAmounts(f,normal,0,0); assertRefundAmounts(f,extra,0,100);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund_item WHERE tenant_id=? AND component_type='EXCESS'",tenant));
    }

    @Test void compensationForDifferentOrdersCanCreateConcurrentlyWithoutGapLockCycle() throws Exception {
        Fixture f=paymentFixture(3); long tenant=f.actor().tenantId();
        var sources=new ArrayList<com.lrj.trade.payment.model.PaymentReceipt>();
        for(int i=0;i<2;i++) {
            var order=factOrder(f,"independent-compensation-"+i);
            var pay=createPayment(f.actor(),"pay-"+i,order.orderNo(),"TEST_A","return");
            sources.add(receive(f,pay,"mismatch-"+i,99,"CNY"));
        }
        var gate=new CyclicBarrier(2);
        var policy=org.springframework.test.util.AopTestUtils.<com.lrj.trade.refund.RefundPolicyService>getUltimateTargetObject(refundPolicies);
        // 两个事务均锁住独立来源后同时进入唯一键插入，防只测同收款串行化而漏掉间隙锁循环。
        doAnswer(call->{ gate.await(10,TimeUnit.SECONDS); return call.callRealMethod(); })
            .when(policy).evaluate(tenant,"CNY",99,true);
        var index=new AtomicLong();
        var results=parallel(2,()->refunds.requestCompensation(tenant,sources.get((int)index.getAndIncrement()).receiptNo()));
        assertEquals(2,results.stream().map(com.lrj.trade.refund.model.RefundView::refundNo).distinct().count());
        for(var source:sources) assertRefundAmounts(f,source,99,0);
    }

    @Test void compensationWithoutNormalPaymentUsesActualCurrencyAndDoesNotReviveOrder() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(); var order=factOrder(f,"mismatch-compensation");
        var pay=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var source=receive(f,pay,"mismatch-usd",99,"USD");
        var before=orders.read(f.actor(),order.orderNo()); var stock=balance(f);
        var refund=refunds.requestCompensation(tenant,source.receiptNo()); assertEquals(99,refund.amount()); assertEquals("USD",refund.currency());
        approveRefund(f,refund,true,1); var plan=prepareRefund(f,refund);
        assertEquals("USD",plan.currency()); assertEquals("mismatch-usd",plan.providerTransactionNo());
        completeRefund(f,refund,"provider-usd");
        assertEquals(before,orders.read(f.actor(),order.orderNo())); assertEquals(stock,balance(f)); assertFalse(members.me(f.actor()).everPaid());
        assertRefundAmounts(f,source,0,99);
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type IN ('trade.order-evidence-changed.v1','trade.refund-succeeded.v1')",tenant));
    }

    @Test void compensationRejectionAndExpiryReleaseWithoutRecreation() {
        for(boolean reject:List.of(true,false)) {
            Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(); var order=factOrder(f,"released-compensation");
            var pay=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
            paymentObservations.closed(tenant,pay.activeAttemptNo(),"sandbox:terminal");
            var source=receive(f,pay,"late",100,"CNY"); var before=orders.read(f.actor(),order.orderNo());
            var refund=refunds.requestCompensation(tenant,source.receiptNo());
            if(reject) approveRefund(f,refund,false,1);
            else { var later=clock.instant().plus(java.time.Duration.ofDays(2)); when(clock.instant()).thenReturn(later);
                new TransactionTemplate(transactions).executeWithoutResult(tx->refunds.expireApproval(tenant,refund.refundNo())); }
            jdbc.update("DELETE FROM tc_command WHERE tenant_id=? AND operation='refund.compensation'",tenant);
            var replay=refunds.requestCompensation(tenant,source.receiptNo());
            assertEquals(refund.refundNo(),replay.refundNo()); assertEquals(reject?"REJECTED":"EXPIRED",replay.status());
            assertRefundAmounts(f,source,0,0); assertEquals(before,orders.read(f.actor(),order.orderNo()));
            assertEquals(1,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",tenant));
            assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type IN ('trade.order-evidence-changed.v1','trade.refund-succeeded.v1')",tenant));
            fixedBusinessClock();
        }
    }

    @Test void compensationCreationRollbackAndUnknownKeepFundsSafe() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId(); var order=factOrder(f,"compensation-rollback");
        var pay=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return"); var source=receive(f,pay,"wrong",99,"CNY");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            refunds.requestCompensation(tenant,source.receiptNo()); throw new IllegalStateException("rollback");
        }));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",tenant)); assertRefundAmounts(f,source,0,0);
        var refund=refunds.requestCompensation(tenant,source.receiptNo()); approveRefund(f,refund,true,1);
        assertEquals("CREATE",prepareRefund(f,refund).action()); assertEquals("QUERY",prepareRefund(f,refund).action());
        assertCode("REFUND_APPROVAL_INVALID",()->new TransactionTemplate(transactions).executeWithoutResult(tx->refunds.expireApproval(tenant,refund.refundNo())));
        assertRefundAmounts(f,source,99,0);
        new TransactionTemplate(transactions).executeWithoutResult(tx->refunds.confirmFailed(tenant,refund.refundNo(),"certified-failure"));
        assertRefundAmounts(f,source,0,0); assertEquals("FAILED",refunds.requestCompensation(tenant,source.receiptNo()).status());
    }

    @Test void approvalRejectionAuditSurvivesOuterRollback() {
        long tenant=TENANTS.incrementAndGet(); var actor=new Actor(tenant,"https://identity.test","workflow");
        var id=new java.util.concurrent.atomic.AtomicReference<String>();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            id.set(rejectionAudit.record(actor,"private.jwt.body","REFUND_APPROVAL_UNTRUSTED"));
            throw new IllegalStateException("outer rollback");
        }));
        assertEquals("REFUND_APPROVAL_UNTRUSTED:"+Checks.hash("private.jwt.body"),jdbc.queryForObject(
            "SELECT reason FROM tc_operation_audit WHERE tenant_id=? AND resource_id=?",String.class,tenant,id.get()));
        assertEquals(actor.key(),jdbc.queryForObject("SELECT actor_key FROM tc_operation_audit WHERE tenant_id=? AND resource_id=?",String.class,tenant,id.get()));
    }

    @Test void approvalHttpBusinessRejectionIsAuditedAndAuditFailureNeverAccepts() throws Exception {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId();
        var refund=requestRefund(f,paidRefundOrder(f,"rejection-audit",1),"approval",100);
        var caller=new Actor(tenant,"https://identity.test","workflow-audit");
        jwt("workflow-audit","trade.refund.approval",tenant);
        // 自审业务拒绝经过真实受理事务回滚；独立拒绝记录仍须可用响应traceId定位。
        var proof=approvalReceipt(f,refund,"self-http",f.actor());
        when(approvalVerifier.verify(caller,"private.jwt.body")).thenReturn(proof);
        String path="/api/v1/trade/integrations/refund-approvals",body="{\"signedReceipt\":\"private.jwt.body\"}";
        var response=request("POST",path,"workflow-audit",body);
        assertEquals(422,response.statusCode(),response.body());
        var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
        assertEquals("REFUND_APPROVAL_INVALID",json.get("code").asText());
        assertFalse(json.get("retryable").asBoolean());
        String trace=json.get("traceId").asText(); assertTrue(trace.startsWith("ARJ-"));
        assertEquals("REFUND_APPROVAL_INVALID:"+Checks.hash("private.jwt.body"),jdbc.queryForObject(
            "SELECT reason FROM tc_operation_audit WHERE tenant_id=? AND resource_id=?",String.class,tenant,trace));
        assertEquals(caller.key(),jdbc.queryForObject("SELECT actor_key FROM tc_operation_audit WHERE tenant_id=? AND resource_id=?",String.class,tenant,trace));
        var writer=org.springframework.test.util.AopTestUtils.<AuditWriter>getUltimateTargetObject(auditWriter);
        doThrow(new IllegalStateException("audit storage unavailable")).when(writer).append(eq(caller),eq("refund.approval.rejected-request"),anyString(),anyString(),anyString(),anyLong(),anyLong());
        var unavailable=request("POST",path,"workflow-audit",body);
        assertEquals(503,unavailable.statusCode(),unavailable.body());
        var error=tools.jackson.databind.json.JsonMapper.builder().build().readTree(unavailable.body());
        assertEquals("REFUND_APPROVAL_AUDIT_UNAVAILABLE",error.get("code").asText()); assertTrue(error.get("retryable").asBoolean());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='refund.approval.rejected-request'",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='refund.approved'",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_RECOVER' AND wake_generation<>0",tenant));
        assertEquals("PENDING_APPROVAL",refunds.read(f.actor(),refund.refundNo()).status()); assertApplicationAmounts(f,100,0);
    }

    @Test void approvalHttpRealAccessAndReceiptSignaturesReachAtomicDatabaseAcceptance() throws Exception {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId();
        var refund=requestRefund(f,paidRefundOrder(f,"approval-real-rsa",1),"approval",100);
        var accessKey=new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("access-key").generate();
        var receiptKey=new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("receipt-key").generate();
        var keys=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        for(var entry:Map.of("/access-keys",accessKey,"/receipt-keys",receiptKey).entrySet()) keys.createContext(entry.getKey(),exchange->{
            byte[] bytes=new com.nimbusds.jose.jwk.JWKSet(entry.getValue().toPublicJWK()).toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json"); exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        }); keys.start();
        try {
            String jwks="http://127.0.0.1:"+keys.getAddress().getPort();
            var realAccess=new SecurityConfiguration().jwtDecoder("https://identity.test",jwks+"/access-keys","transaction-center");
            var realReceipt=RefundApprovalVerifier.create("https://workflow.test",jwks+"/receipt-keys","transaction-refund-approval",
                "https://identity.test","workflow-service","https://identity.test",clock);
            // 替身仅把调用委托给真实Nimbus验证，不返回预制身份或已验证记录；所有HTTP身份与决定均来自RSA验签。
            when(decoder.decode(anyString())).thenAnswer(call->realAccess.decode(call.getArgument(0)));
            when(approvalVerifier.verify(any(),anyString())).thenAnswer(call->realReceipt.verify(call.getArgument(0),call.getArgument(1)));
            Instant accessNow=Instant.now();
            var accessClaims=new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("https://identity.test").audience("transaction-center")
                .subject("workflow-service").claim("tenant_id",Long.toString(tenant)).claim("scope","trade.refund.approval")
                .issueTime(java.util.Date.from(accessNow)).expirationTime(java.util.Date.from(accessNow.plusSeconds(120))).build();
            String access=signedApprovalTestJwt(accessKey,accessClaims);
            var claims=new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("https://workflow.test").audience("transaction-refund-approval")
                .subject("workflow-service").jwtID("http-real-event").claim("tenant_id",Long.toString(tenant))
                .claim("receipt_kind","REFUND_APPROVAL_DECISION_V1").claim("schema_version",1)
                .claim("approver_issuer","https://identity.test").claim("approver_subject","human-reviewer")
                .claim("refund_no",refund.refundNo()).claim("approval_ref",refund.approvalRef())
                .claim("decision","APPROVE").claim("decision_version",1).claim("evidence_ref","workflow:real-event")
                .issueTime(java.util.Date.from(clock.instant())).expirationTime(java.util.Date.from(clock.instant().plusSeconds(120))).build();
            var attacker=new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("receipt-key").generate();
            String path="/api/v1/trade/integrations/refund-approvals";
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            String forged=json.writeValueAsString(Map.of("signedReceipt",signedApprovalTestJwt(attacker,claims)));
            assertEquals(403,request("POST",path,access,forged).statusCode());
            assertEquals(403,request("POST",path,access,json.writeValueAsString(Map.of("signedReceipt",signedApprovalTestJwt(accessKey,claims)))).statusCode());
            assertEquals(0,count("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?",tenant));
            assertEquals("PENDING_APPROVAL",refunds.read(f.actor(),refund.refundNo()).status());
            var wrongTenant=new com.nimbusds.jwt.JWTClaimsSet.Builder(claims).claim("tenant_id",Long.toString(tenant+1)).build();
            assertEquals(403,request("POST",path,access,json.writeValueAsString(Map.of("signedReceipt",signedApprovalTestJwt(receiptKey,wrongTenant)))).statusCode());
            var wrongAccess=new com.nimbusds.jwt.JWTClaimsSet.Builder(accessClaims).audience("other-service").build();
            String valid=signedApprovalTestJwt(receiptKey,claims),body=json.writeValueAsString(Map.of("signedReceipt",valid));
            assertEquals(401,request("POST",path,signedApprovalTestJwt(accessKey,wrongAccess),body).statusCode());
            var accepted=request("POST",path,access,body); assertEquals(200,accepted.statusCode(),accepted.body());
            assertEquals(accepted.body(),request("POST",path,access,body).body());
            assertEquals("READY",refunds.read(f.actor(),refund.refundNo()).status());
            assertEquals(1,count("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?",tenant));
            assertEquals(Checks.hash(valid),jdbc.queryForObject("SELECT payload_hash FROM tc_refund_approval_receipt WHERE tenant_id=?",String.class,tenant));
            assertEquals(new Actor(tenant,"https://identity.test","human-reviewer").key(),
                jdbc.queryForObject("SELECT approval_actor_key FROM tc_refund WHERE tenant_id=? AND refund_no=?",String.class,tenant,refund.refundNo()));
            assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='refund.approved'",tenant));
            assertApplicationAmounts(f,100,0);
        } finally { keys.stop(0); }
    }
    private String signedApprovalTestJwt(com.nimbusds.jose.jwk.RSAKey key,com.nimbusds.jwt.JWTClaimsSet claims) throws Exception {
        var jwt=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key)); return jwt.serialize();
    }

    @Test void approvalHttpRequiresDedicatedScopeAndAtomicVerifiedReceipt() throws Exception {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId();
        var refund=requestRefund(f,paidRefundOrder(f,"approval-http",1),"approval",100);
        var proof=approvalReceipt(f,refund,"http-event",new Actor(tenant,"https://identity.test","approver"));
        jwt("approval-http-member","",tenant); jwt("approval-http-admin","trade.admin",tenant);
        jwt("workflow-service","trade.refund.approval",tenant);
        var caller=new Actor(tenant,"https://identity.test","workflow-service");
        when(approvalVerifier.verify(caller,"signed")).thenReturn(proof);
        when(approvalVerifier.verify(caller,"bad")).thenThrow(new TradeException("REFUND_APPROVAL_UNTRUSTED","审批回执认证失败"));
        String path="/api/v1/trade/integrations/refund-approvals",body="{\"signedReceipt\":\"signed\"}";
        assertEquals(401,request("POST",path,null,body).statusCode());
        assertEquals(403,request("POST",path,"approval-http-member",body).statusCode());
        assertEquals(403,request("POST",path,"approval-http-admin",body).statusCode());
        assertEquals(400,request("POST",path,"workflow-service","{\"signedReceipt\":\"signed\",\"approve\":true}").statusCode());
        for(String invalid:List.of("{}","{\"signedReceipt\":null}","{\"signedReceipt\":\" \"}","{\"signedReceipt\":\""+"x".repeat(16385)+"\"}"))
            assertEquals(400,request("POST",path,"workflow-service",invalid).statusCode());
        verify(approvalVerifier,never()).verify(any(),any());
        assertEquals(403,request("POST",path,"workflow-service","{\"signedReceipt\":\"bad\"}").statusCode());
        assertEquals("PENDING_APPROVAL",refunds.read(f.actor(),refund.refundNo()).status());
        var receiver=org.springframework.test.util.AopTestUtils.<com.lrj.trade.refund.RefundApprovalReceiptService>getUltimateTargetObject(approvalReceipts);
        doThrow(new TradeException("CALLBACK_ENCRYPTION_UNAVAILABLE","缺历史密钥")).doCallRealMethod().when(receiver).accept(proof);
        assertEquals(503,request("POST",path,"workflow-service",body).statusCode());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='refund.approved'",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_RECOVER' AND wake_generation<>0",tenant));
        var accepted=request("POST",path,"workflow-service",body); assertEquals(200,accepted.statusCode(),accepted.body());
        assertEquals(accepted.body(),request("POST",path,"workflow-service",body).body());
        assertEquals("READY",refunds.read(f.actor(),refund.refundNo()).status());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?",tenant));
        assertApplicationAmounts(f,100,0);
    }

    @Test void approvalReceiptCommitsEncryptedEvidenceAndDeduplicates() {
        Fixture f=paymentFixture(1); var refund=requestRefund(f,paidRefundOrder(f,"approval-receipt",1),"approval",100);
        var receipt=approvalReceipt(f,refund,"one",new Actor(f.actor().tenantId(),"https://identity.test","approver"));
        String number=approvalReceipts.accept(receipt);
        assertEquals(number,approvalReceipts.accept(receipt));
        assertEquals("READY",refunds.read(f.actor(),refund.refundNo()).status()); assertApplicationAmounts(f,100,0);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='refund.approved'",f.actor().tenantId()));
        byte[] cipher=jdbc.queryForObject("SELECT payload_cipher FROM tc_refund_approval_receipt WHERE tenant_id=?",byte[].class,f.actor().tenantId());
        assertFalse(new String(cipher,java.nio.charset.StandardCharsets.UTF_8).contains(receipt.signedReceipt()));
        var changed=new com.lrj.trade.refund.model.VerifiedApprovalReceipt(receipt.sourceIssuer(),receipt.sourceSubject(),receipt.eventId(),
            receipt.approver(),receipt.refundNo(),receipt.approvalRef(),1,false,receipt.evidenceRef(),receipt.issuedAt(),receipt.expiresAt(),receipt.signedReceipt());
        assertCode("REFUND_APPROVAL_EVENT_CONFLICT",()->approvalReceipts.accept(changed));
    }
    @Test void approvalReceiptRejectedBusinessDecisionLeavesNoAcceptedEvidence() {
        Fixture f=paymentFixture(1); var refund=requestRefund(f,paidRefundOrder(f,"approval-self",1),"approval",100);
        var self=approvalReceipt(f,refund,"self",f.actor());
        assertCode("REFUND_APPROVAL_INVALID",()->approvalReceipts.accept(self));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals("PENDING_APPROVAL",refunds.read(f.actor(),refund.refundNo()).status()); assertApplicationAmounts(f,100,0);
    }
    @Test void approvalReceiptConcurrentReplayHasOneEvidenceAndOneDecision() throws Exception {
        Fixture f=paymentFixture(1); var refund=requestRefund(f,paidRefundOrder(f,"approval-race",1),"approval",100);
        var receipt=approvalReceipt(f,refund,"race",new Actor(f.actor().tenantId(),"https://identity.test","approver"));
        var outcomes=parallel(4,()->approvalReceipts.accept(receipt));
        assertEquals(1,new java.util.HashSet<>(outcomes).size());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='refund.approved'",f.actor().tenantId()));
    }
    @Test void approvalReceiptExpiredAndTamperedProofCannotApply() {
        Fixture f=paymentFixture(1); var refund=requestRefund(f,paidRefundOrder(f,"approval-expiry",1),"approval",100);
        var receipt=approvalReceipt(f,refund,"expiry",new Actor(f.actor().tenantId(),"https://identity.test","approver"));
        var now=clock.instant(); when(clock.instant()).thenReturn(receipt.expiresAt());
        assertCode("REFUND_APPROVAL_UNTRUSTED",()->approvalReceipts.accept(receipt));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?",f.actor().tenantId()));
        when(clock.instant()).thenReturn(now); approvalReceipts.accept(receipt);
        jdbc.update("UPDATE tc_refund_approval_receipt SET payload_cipher=? WHERE tenant_id=?",new byte[]{1,2,3},f.actor().tenantId());
        assertCode("CALLBACK_EVIDENCE_INVALID",()->approvalReceipts.accept(receipt));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='refund.approved'",f.actor().tenantId()));
    }
    @Test void approvalReceiptExpiryAfterDomainWriteRollsBackEverything() {
        Fixture f=paymentFixture(1); var refund=requestRefund(f,paidRefundOrder(f,"approval-post-expiry",1),"approval",100);
        var receipt=approvalReceipt(f,refund,"post-expiry",new Actor(f.actor().tenantId(),"https://identity.test","approver"));
        var target=org.springframework.test.util.AopTestUtils.<com.lrj.trade.jobs.JobLeaseService>getUltimateTargetObject(jobLeases);
        doAnswer(call->{ var result=call.callRealMethod(); when(clock.instant()).thenReturn(receipt.expiresAt()); return result; })
            .doCallRealMethod().when(target).wake(f.actor().tenantId(),JobType.REFUND_RECOVER,refund.refundNo());
        assertCode("REFUND_APPROVAL_UNTRUSTED",()->approvalReceipts.accept(receipt));
        assertEquals("PENDING_APPROVAL",refunds.read(f.actor(),refund.refundNo()).status()); assertApplicationAmounts(f,100,0);
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='refund.approved'",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='refund.ready.internal.v0'",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_RECOVER' AND wake_generation<>0",f.actor().tenantId()));
    }
    @Test void approvalReceiptSourceEventCannotMoveAcrossTenants() {
        Fixture first=paymentFixture(1); var refund=requestRefund(first,paidRefundOrder(first,"approval-source-one",1),"approval",100);
        approvalReceipts.accept(approvalReceipt(first,refund,"global-event",new Actor(first.actor().tenantId(),"https://identity.test","approver")));
        Fixture other=paymentFixture(1); var second=requestRefund(other,paidRefundOrder(other,"approval-source-two",1),"approval",100);
        assertCode("REFUND_APPROVAL_EVENT_CONFLICT",()->approvalReceipts.accept(approvalReceipt(other,second,"global-event",
            new Actor(other.actor().tenantId(),"https://identity.test","approver"))));
        assertEquals("PENDING_APPROVAL",refunds.read(other.actor(),second.refundNo()).status()); assertApplicationAmounts(other,100,0);
    }
    @Test void approvalReceiptLegitimateRejectionReleasesOnlyOnce() {
        Fixture f=paymentFixture(1); var refund=requestRefund(f,paidRefundOrder(f,"approval-reject",1),"approval",100);
        var r=approvalReceipt(f,refund,"reject",new Actor(f.actor().tenantId(),"https://identity.test","approver"));
        var reject=new com.lrj.trade.refund.model.VerifiedApprovalReceipt(r.sourceIssuer(),r.sourceSubject(),r.eventId(),r.approver(),
            r.refundNo(),r.approvalRef(),r.decisionVersion(),false,r.evidenceRef(),r.issuedAt(),r.expiresAt(),r.signedReceipt());
        String number=approvalReceipts.accept(reject); assertEquals(number,approvalReceipts.accept(reject));
        assertEquals("REJECTED",refunds.read(f.actor(),refund.refundNo()).status()); assertApplicationAmounts(f,0,0);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='refund.rejected'",f.actor().tenantId()));
    }
    // 本夹具代表认证适配层产物；RSA防伪造另由RefundApprovalVerifierTest验证，生产HTTP默认关闭。
    private com.lrj.trade.refund.model.VerifiedApprovalReceipt approvalReceipt(Fixture f,com.lrj.trade.refund.model.RefundView refund,String event,Actor approver) {
        return new com.lrj.trade.refund.model.VerifiedApprovalReceipt("https://workflow.test","workflow-service",event,approver,
            refund.refundNo(),refund.approvalRef(),1,true,"workflow:"+event,clock.instant(),clock.instant().plusSeconds(120),"test-signed-receipt:"+event);
    }
    @Autowired com.lrj.trade.payment.PaymentChannelBindingPort channelBindings;
    @Autowired com.lrj.trade.refund.RefundRecoveryBackfillService refundBackfill;

    @Test void refundRecoveryMissingCredentialRetriesWithoutReleasingFunds() {
        Fixture f=paymentFixture(1); var refund=readyRecoveryRefund(f); long tenant=f.actor().tenantId();
        var adapter=mock(com.lrj.trade.refund.RefundChannelAdapter.class);
        var plan=prepareRefund(f,refund);
        when(adapter.channelCode()).thenReturn(plan.channelCode());
        var channels=new com.lrj.trade.refund.RefundChannelService(List.of(adapter),List.of(refundInbox),(t,a)->{
            throw new TradeException("PAYMENT_CHANNEL_BINDING_UNAVAILABLE","托管引用暂不可用");
        });
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.RETRY),refundRecoveryWith(channels).runBatch(tenant,"missing-key",1));
        verify(adapter,never()).execute(any(),any(),any());
        assertApplicationAmounts(f,100,0);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_RECOVER' AND status='READY'",tenant));
    }

    @Test void refundRecoveryInitialDispatchAndUnknownKeepStableRequestAndFunds() {
        Fixture f=paymentFixture(1); var refund=readyRecoveryRefund(f); long tenant=f.actor().tenantId();
        List<com.lrj.trade.refund.RefundExecutionPort.Plan> seen=new ArrayList<>();
        var worker=refundRecoveryWith((t,plan)->{ seen.add(plan); return com.lrj.trade.refund.RefundChannelPort.Outcome.UNKNOWN; });
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.RETRY),worker.runBatch(tenant,"first",1));
        assertEquals("CREATE",seen.getFirst().action()); assertEquals("SUBMITTED",refunds.read(f.actor(),refund.refundNo()).status());
        assertApplicationAmounts(f,100,0); makeRefundRecoveryDue(tenant);
        worker.runBatch(tenant,"query",1); assertEquals("QUERY",seen.get(1).action());
        assertEquals(seen.getFirst().merchantRefundNo(),seen.get(1).merchantRefundNo()); assertApplicationAmounts(f,100,0);
    }

    @Test void refundRecoveryCrashBeforeNetworkCanReplayOnlyCertifiedSameRequest() {
        Fixture f=paymentFixture(1); var refund=readyRecoveryRefund(f); long tenant=f.actor().tenantId();
        var original=prepareRefund(f,refund); // 模拟派发标记提交后、网络发送前进程退出。
        var adapter=mock(com.lrj.trade.refund.RefundChannelAdapter.class); List<String> actions=new ArrayList<>();
        when(adapter.channelCode()).thenReturn(original.channelCode()); when(adapter.supportsSafeCreateReplay()).thenReturn(true);
        when(adapter.execute(any(),any(),any())).thenAnswer(call->{
            com.lrj.trade.refund.RefundExecutionPort.Plan plan=call.getArgument(0);
            actions.add(plan.action()); assertEquals(original.merchantRefundNo(),plan.merchantRefundNo());
            if(plan.action().equals("QUERY")) return new com.lrj.trade.refund.RefundChannelAdapter.Reply(
                com.lrj.trade.refund.RefundChannelAdapter.State.REPLAYABLE_ABSENCE,null);
            return new com.lrj.trade.refund.RefundChannelAdapter.Reply(com.lrj.trade.refund.RefundChannelAdapter.State.EVIDENCE,
                refundEvidence(plan,"recovered",100,"CNY",plan.providerTransactionNo()));
        });
        var channels=new com.lrj.trade.refund.RefundChannelService(List.of(adapter),List.of(refundInbox),channelBindings);
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.RETRY),refundRecoveryWith(channels).runBatch(tenant,"recovered",1));
        assertEquals(List.of("QUERY","CREATE"),actions); assertApplicationAmounts(f,100,0);
        refundCallbackWorker.runBatch(tenant,"evidence",1);
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.DONE),refundRecoveryWith(channels).runBatch(tenant,"terminal",1));
        assertApplicationAmounts(f,0,100);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",tenant));
    }

    @Test void refundRecoveryUncertifiedAbsenceDoesNotEnableCreate() {
        Fixture f=paymentFixture(1); var refund=readyRecoveryRefund(f); long tenant=f.actor().tenantId(); var plan=prepareRefund(f,refund);
        var adapter=mock(com.lrj.trade.refund.RefundChannelAdapter.class);
        when(adapter.channelCode()).thenReturn(plan.channelCode());
        when(adapter.execute(any(),any(),any())).thenReturn(new com.lrj.trade.refund.RefundChannelAdapter.Reply(
            com.lrj.trade.refund.RefundChannelAdapter.State.REPLAYABLE_ABSENCE,null));
        var worker=refundRecoveryWith(new com.lrj.trade.refund.RefundChannelService(List.of(adapter),List.of(refundInbox),channelBindings));
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.RETRY),worker.runBatch(tenant,"no-cert",1));
        verify(adapter,times(1)).execute(any(),any(),any()); assertApplicationAmounts(f,100,0);
        assertEquals("SUBMITTED",refunds.read(f.actor(),refund.refundNo()).status());
    }

    @Test void refundRecoveryCallbackWinningDuringQueryPreventsReplay() {
        Fixture f=paymentFixture(1); var refund=readyRecoveryRefund(f); long tenant=f.actor().tenantId(); prepareRefund(f,refund);
        var calls=new AtomicLong();
        var worker=refundRecoveryWith((t,plan)->{
            assertEquals("QUERY",plan.action()); calls.incrementAndGet();
            refundInbox.accept(t,refundEvidence(plan,"race",100,"CNY",plan.providerTransactionNo()));
            refundCallbackWorker.runBatch(t,"callback",1);
            return com.lrj.trade.refund.RefundChannelPort.Outcome.REPLAYABLE_ABSENCE;
        });
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.DONE),worker.runBatch(tenant,"query",1));
        assertEquals(1,calls.get()); assertApplicationAmounts(f,0,100);
    }

    @Test void refundRecoveryExpiredLeaseCannotDispatchAndLateEvidenceSurvivesLeaseLoss() {
        Fixture f=paymentFixture(1); var refund=readyRecoveryRefund(f); long tenant=f.actor().tenantId();
        var old=jobLeases.claim(tenant,JobType.REFUND_RECOVER,"old",1,30000).getFirst(); expireLease(old);
        var noNetwork=refundRecoveryWith((t,plan)->{ fail("失效租约不能发送网络"); return null; });
        assertEquals(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.LEASE_LOST,noNetwork.processLease(old));
        assertEquals("READY",refunds.read(f.actor(),refund.refundNo()).status()); assertApplicationAmounts(f,100,0);
        var current=jobLeases.claim(tenant,JobType.REFUND_RECOVER,"current",1,30000).getFirst();
        var worker=refundRecoveryWith((t,plan)->{
            expireLease(current);
            refundInbox.accept(t,refundEvidence(plan,"late-evidence",100,"CNY",plan.providerTransactionNo()));
            return com.lrj.trade.refund.RefundChannelPort.Outcome.EVIDENCE_ACCEPTED;
        });
        assertEquals(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.LEASE_LOST,worker.processLease(current));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund_callback WHERE tenant_id=?",tenant));
        refundCallbackWorker.runBatch(tenant,"late",1); assertApplicationAmounts(f,0,100);
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.DONE),noNetwork.runBatch(tenant,"new",1));
    }

    @Test void refundRecoveryApprovalDeadlineNeverDispatchesAndReleasesOnce() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId();
        var refund=requestRefund(f,paidRefundOrder(f,"waiting",1),"waiting",100);
        // 主库领取使用真实UTC；不能让固定业务日期使“未来任务”夹具随日历过期。
        jdbc.update("UPDATE tc_job_lease SET next_run_at=UTC_TIMESTAMP(3)+INTERVAL 1 DAY WHERE tenant_id=? AND job_type='REFUND_RECOVER' AND business_key=? AND status='READY'",tenant,refund.refundNo());
        assertTrue(refundRecoveryWorker.runBatch(tenant,"early",1).isEmpty());
        makeRefundRecoveryDue(tenant);
        var worker=refundRecoveryWith((t,p)->{ fail("待审批和过期都不能派发"); return null; });
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.RETRY),worker.runBatch(tenant,"waiting",1));
        assertApplicationAmounts(f,100,0);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-08T08:00:00.123Z")); makeRefundRecoveryDue(tenant);
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.DONE),worker.runBatch(tenant,"expired",1));
        assertEquals("EXPIRED",refunds.read(f.actor(),refund.refundNo()).status()); assertApplicationAmounts(f,0,0);
        assertTrue(worker.runBatch(tenant,"again",1).isEmpty());
    }

    @Test void refundRecoveryApprovalWakeSurvivesRunningWorkerRetry() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId();
        var refund=requestRefund(f,paidRefundOrder(f,"wake",1),"wake",100); makeRefundRecoveryDue(tenant);
        var lease=jobLeases.claim(tenant,JobType.REFUND_RECOVER,"waiting",1,30000).getFirst();
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            assertEquals("WAIT_APPROVAL",refunds.prepareRecovery(tenant,refund.refundNo()).plan().action());
            approveRefund(f,refund,true,1);
            jobLeases.retry(lease,300000,"REFUND_APPROVAL_PENDING");
        });
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.RETRY),
            refundRecoveryWith((t,p)->{ assertEquals("CREATE",p.action()); return com.lrj.trade.refund.RefundChannelPort.Outcome.PENDING; }).runBatch(tenant,"approved",1));
        assertApplicationAmounts(f,100,0);
    }

    @Test void refundRecoveryTaskFailureRollsBackApplicationAndApproval() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"task-fail",1);
        var target=org.springframework.test.util.AopTestUtils.<com.lrj.trade.jobs.JobLeaseService>getUltimateTargetObject(jobLeases);
        doThrow(new IllegalStateException("测试任务预约失败")).doCallRealMethod().when(target)
            .schedule(eq(tenant),eq(JobType.REFUND_RECOVER),anyString(),any());
        assertThrows(IllegalStateException.class,()->requestRefund(f,order,"failed",100));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",tenant)); assertApplicationAmounts(f,0,0);
        var refund=requestRefund(f,order,"retry",100);
        doThrow(new IllegalStateException("测试唤醒失败")).doCallRealMethod().when(target)
            .wake(eq(tenant),eq(JobType.REFUND_RECOVER),anyString());
        assertThrows(IllegalStateException.class,()->approveRefund(f,refund,true,1));
        assertEquals("PENDING_APPROVAL",refunds.read(f.actor(),refund.refundNo()).status()); assertApplicationAmounts(f,100,0);
    }

    @Test void refundRecoveryBackfillIsBoundedIdempotentAndDoesNotRestartTerminalTasks() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var refund=readyRecoveryRefund(f);
        // 仅隔离测试库模拟旧版本申请遗漏任务；正式库不执行删除。
        jdbc.update("DELETE FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_RECOVER'",tenant);
        long cursor=refundBackfill.enqueuePage(tenant,0,1);
        assertTrue(cursor>0); assertEquals(cursor,refundBackfill.enqueuePage(tenant,cursor,1));
        assertEquals(cursor,refundBackfill.enqueuePage(tenant,0,1));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_RECOVER'",tenant));
        var plan=prepareRefund(f,refund);
        refundInbox.accept(tenant,refundEvidence(plan,"backfill",100,"CNY",plan.providerTransactionNo()));
        refundCallbackWorker.runBatch(tenant,"callback",1); refundRecoveryWorker.runBatch(tenant,"done",1);
        assertEquals(0,refundBackfill.enqueuePage(tenant,0,100)); assertTrue(refundRecoveryWorker.runBatch(tenant,"again",1).isEmpty());
        assertCode("INVALID_ARGUMENT",()->refundBackfill.enqueuePage(tenant,-1,1));
        assertCode("INVALID_ARGUMENT",()->refundBackfill.enqueuePage(tenant,0,101));
    }

    @Test void refundRecoveryBackfillWakesFutureTaskApprovedByLegacyWriter() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId();
        var refund=requestRefund(f,paidRefundOrder(f,"legacy-approval",1),"legacy-approval",100);
        // 主库领取使用真实UTC；不能让固定业务日期使“未来任务”夹具随日历过期。
        jdbc.update("UPDATE tc_job_lease SET next_run_at=UTC_TIMESTAMP(3)+INTERVAL 1 DAY WHERE tenant_id=? AND job_type='REFUND_RECOVER' AND business_key=? AND status='READY'",tenant,refund.refundNo());
        var target=org.springframework.test.util.AopTestUtils.<com.lrj.trade.jobs.JobLeaseService>getUltimateTargetObject(jobLeases);
        // 模拟旧V015批准逻辑：保留有效批准事实，但没有新增的任务唤醒。
        doNothing().doCallRealMethod().when(target).wake(eq(tenant),eq(JobType.REFUND_RECOVER),eq(refund.refundNo()));
        approveRefund(f,refund,true,1);
        assertTrue(jobLeases.claim(tenant,JobType.REFUND_RECOVER,"too-early",1,30000).isEmpty());
        assertTrue(refundBackfill.enqueuePage(tenant,0,100)>0);
        assertEquals(List.of(com.lrj.trade.jobs.RefundRecoveryWorker.Outcome.RETRY),
            refundRecoveryWith((t,p)->{ assertEquals("CREATE",p.action()); return com.lrj.trade.refund.RefundChannelPort.Outcome.UNKNOWN; })
                .runBatch(tenant,"backfilled",1));
        assertApplicationAmounts(f,100,0);
    }

    private com.lrj.trade.refund.model.RefundView readyRecoveryRefund(Fixture f) {
        var order=paidRefundOrder(f,"recovery",1); refundPolicies.configure(f.actor(),"policy","CNY",100,0);
        return requestRefund(f,order,"recovery",100);
    }
    private com.lrj.trade.jobs.RefundRecoveryWorker refundRecoveryWith(com.lrj.trade.refund.RefundChannelPort channels) {
        return new com.lrj.trade.jobs.RefundRecoveryWorker(jobLeases,refunds,channels,transactions,clock);
    }
    private void makeRefundRecoveryDue(long tenant) {
        jdbc.update("UPDATE tc_job_lease SET next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND job_type='REFUND_RECOVER' AND status='READY'",tenant);
    }

    @Autowired com.lrj.trade.refund.RefundInboxService refundInbox;
    @Autowired com.lrj.trade.jobs.RefundCallbackWorker refundCallbackWorker;
    @Autowired com.lrj.trade.refund.mapper.RefundInboxMapper refundInboxMapper;
    @Autowired EvidenceProtectionPort evidenceProtection;

    @Test void refundInboxConcurrentReceptionAndDuplicateWorkersAreExactlyOnceLocally() throws Exception {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        var facts=tradeFacts.read(tenant,Long.parseLong(members.me(f.actor()).memberId())); var stock=balance(f);
        var input=refundEvidence(plan,"success",100,"CNY",plan.providerTransactionNo());
        var numbers=parallel(20,()->refundInbox.receive(tenant,input));
        assertEquals(1,numbers.stream().distinct().count()); String number=numbers.getFirst();
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund_callback WHERE tenant_id=?",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='REFUND_CALLBACK'",tenant));
        assertApplicationAmounts(f,100,0);
        var lease=jobLeases.claim(tenant,JobType.REFUND_CALLBACK,"worker",1,30000).getFirst();
        var outcomes=parallel(10,()->refundCallbackWorker.processLease(lease));
        assertEquals(1,outcomes.stream().filter(x->x==com.lrj.trade.jobs.RefundCallbackWorker.Outcome.DONE).count());
        assertTrue(outcomes.stream().allMatch(x->x==com.lrj.trade.jobs.RefundCallbackWorker.Outcome.DONE || x==com.lrj.trade.jobs.RefundCallbackWorker.Outcome.LEASE_LOST));
        assertEquals("PROCESSED",refundCallbackState(tenant,number)); assertApplicationAmounts(f,0,100);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",tenant));
        assertEquals(number,refundInbox.receive(tenant,input)); assertTrue(refundCallbackWorker.runBatch(tenant,"again",1).isEmpty());
        assertEquals(stock,balance(f)); assertEquals(facts,tradeFacts.read(tenant,Long.parseLong(members.me(f.actor()).memberId())));
    }

    @Test void refundInboxEventConflictCannotOverwriteOriginalEvidence() {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        String number=refundInbox.receive(tenant,refundEvidence(plan,"same",100,"CNY",plan.providerTransactionNo()));
        assertCode("REFUND_CALLBACK_FACT_CONFLICT",()->refundInbox.receive(tenant,refundEvidence(plan,"same",99,"CNY",plan.providerTransactionNo())));
        var original=refundInboxMapper.find(tenant,number).getFirst();
        var changed=new com.lrj.trade.refund.model.VerifiedRefundEvidence(plan.channelCode(),plan.channelMerchantNo(),"same",
            plan.merchantRefundNo(),plan.providerTransactionNo(),"provider-same",com.lrj.trade.refund.model.VerifiedRefundEvidence.Kind.SUCCEEDED,
            100L,"CNY",clock.instant(),"test-cert",new byte[]{8});
        assertCode("REFUND_CALLBACK_FACT_CONFLICT",()->refundInbox.receive(tenant,changed));
        assertArrayEquals(original.payloadCipher(),refundInboxMapper.find(tenant,number).getFirst().payloadCipher());
        assertApplicationAmounts(f,100,0);
        assertCode("NOT_FOUND",()->refundInbox.receive(tenant+100000,refundEvidence(plan,"other",100,"CNY",plan.providerTransactionNo())));
    }

    @Test void refundInboxTaskEnqueueFailureRollsBackReceptionAndRejectsAmbientTransaction() {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        var input=refundEvidence(plan,"enqueue",100,"CNY",plan.providerTransactionNo());
        var target=org.springframework.test.util.AopTestUtils.<com.lrj.trade.jobs.JobLeaseService>getUltimateTargetObject(jobLeases);
        doThrow(new TradeException("TASK_FAILURE","测试入队失败")).when(target).enqueue(eq(tenant),eq(JobType.REFUND_CALLBACK),anyString());
        assertCode("TASK_FAILURE",()->refundInbox.receive(tenant,input));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund_callback WHERE tenant_id=?",tenant));
        doCallRealMethod().when(target).enqueue(eq(tenant),eq(JobType.REFUND_CALLBACK),anyString());
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->new TransactionTemplate(transactions)
            .executeWithoutResult(s->refundInbox.receive(tenant,input)));
        assertNotNull(refundInbox.receive(tenant,input));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->refundInbox.process(tenant,"without-transaction"));
    }

    @Test void refundInboxAbnormalAmountCurrencyAndOriginalAreRetainedForReview() {
        for(int scenario=0;scenario<3;scenario++) {
            Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
            var input=refundEvidence(plan,"abnormal",scenario==0?99:100,scenario==1?"USD":"CNY",
                scenario==2?"wrong-original":plan.providerTransactionNo());
            String number=refundInbox.receive(tenant,input);
            assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.REVIEW),refundCallbackWorker.runBatch(tenant,"review",1));
            assertEquals("REVIEW",refundCallbackState(tenant,number)); assertApplicationAmounts(f,100,0);
            assertEquals(number,refundInbox.receive(tenant,input));
            assertEquals(0,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",tenant));
            assertTrue(refundCallbackWorker.runBatch(tenant,"replay",1).isEmpty());
        }
    }

    @Test void refundInboxReliableFailureAndContradictorySuccessKeepBothEvidenceRecords() {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        var failed=new com.lrj.trade.refund.model.VerifiedRefundEvidence(plan.channelCode(),plan.channelMerchantNo(),"failed",
            plan.merchantRefundNo(),plan.providerTransactionNo(),null,com.lrj.trade.refund.model.VerifiedRefundEvidence.Kind.FAILED_FINAL,
            null,null,clock.instant(),"test-cert",new byte[]{1});
        refundInbox.accept(tenant,failed); assertApplicationAmounts(f,100,0);
        assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.DONE),refundCallbackWorker.runBatch(tenant,"failed",1));
        assertApplicationAmounts(f,0,0); assertEquals("FAILED",refunds.read(f.actor(),plan.refundNo()).status());
        String late=refundInbox.receive(tenant,refundEvidence(plan,"late",100,"CNY",plan.providerTransactionNo()));
        assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.REVIEW),refundCallbackWorker.runBatch(tenant,"late",1));
        assertEquals("REVIEW",refundCallbackState(tenant,late)); assertApplicationAmounts(f,0,0);
        assertEquals(2,count("SELECT COUNT(*) FROM tc_refund_callback WHERE tenant_id=?",tenant));
    }

    @Test void refundInboxStaleLeaseRollsBackFundsAndProcessedMarker() {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        String number=refundInbox.receive(tenant,refundEvidence(plan,"stale",100,"CNY",plan.providerTransactionNo()));
        var old=jobLeases.claim(tenant,JobType.REFUND_CALLBACK,"old",1,30000).getFirst(); expireLease(old);
        assertEquals(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.LEASE_LOST,refundCallbackWorker.processLease(old));
        assertEquals("PENDING",refundCallbackState(tenant,number)); assertApplicationAmounts(f,100,0);
        assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.DONE),refundCallbackWorker.runBatch(tenant,"new",1));
        assertApplicationAmounts(f,0,100);
    }

    @Test void refundInboxTamperingAndCrossNamespaceCipherAreRejected() {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        String number=refundInbox.receive(tenant,refundEvidence(plan,"tamper",100,"CNY",plan.providerTransactionNo()));
        var record=refundInboxMapper.find(tenant,number).getFirst(); byte[] cipher=record.payloadCipher(); cipher[cipher.length-1]^=1;
        jdbc.update("UPDATE tc_refund_callback SET payload_cipher=? WHERE tenant_id=? AND callback_no=?",cipher,tenant,number);
        assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.REVIEW),refundCallbackWorker.runBatch(tenant,"tamper",1));
        assertApplicationAmounts(f,100,0);
        var envelope=evidenceProtection.encrypt("refund-callback-v1","binding",new byte[]{1});
        assertCode("CALLBACK_EVIDENCE_INVALID",()->evidenceProtection.decrypt("other","binding",envelope));
        assertArrayEquals(new byte[]{1},evidenceProtection.decrypt("refund-callback-v1","binding",envelope));
    }

    @Test void refundInboxMissingHistoricalKeyRetriesWithoutReleasingFunds() {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        String number=refundInbox.receive(tenant,refundEvidence(plan,"key",100,"CNY",plan.providerTransactionNo()));
        jdbc.update("UPDATE tc_refund_callback SET key_version='missing' WHERE tenant_id=? AND callback_no=?",tenant,number);
        assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.RETRY),refundCallbackWorker.runBatch(tenant,"key",1));
        assertEquals("PENDING",refundCallbackState(tenant,number)); assertApplicationAmounts(f,100,0);
        jdbc.update("UPDATE tc_refund_callback SET key_version='test-v1' WHERE tenant_id=? AND callback_no=?",tenant,number);
        jdbc.update("UPDATE tc_job_lease SET next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND job_type='REFUND_CALLBACK'",tenant);
        assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.DONE),refundCallbackWorker.runBatch(tenant,"restored",1));
    }

    @Test void refundInboxCommitAcknowledgementLossDoesNotRestartCommittedTask() {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        var input=refundEvidence(plan,"commit",100,"CNY",plan.providerTransactionNo());
        String number=refundInbox.receive(tenant,input);
        var lease=jobLeases.claim(tenant,JobType.REFUND_CALLBACK,"commit",1,30000).getFirst();
        var faulty=new com.lrj.trade.jobs.RefundCallbackWorker(jobLeases,refundInbox,callbackFaultManager(true));
        assertEquals(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.LEASE_LOST,faulty.processLease(lease));
        assertEquals("PROCESSED",refundCallbackState(tenant,number)); assertApplicationAmounts(f,0,100);
        assertEquals(number,refundInbox.receive(tenant,input)); assertTrue(refundCallbackWorker.runBatch(tenant,"again",1).isEmpty());
    }

    @Test void refundInboxStaleReviewCannotChangeEitherTaskOrEvidence() {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        String number=refundInbox.receive(tenant,refundEvidence(plan,"review-stale",99,"CNY",plan.providerTransactionNo()));
        var old=jobLeases.claim(tenant,JobType.REFUND_CALLBACK,"old",1,30000).getFirst(); expireLease(old);
        assertEquals(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.LEASE_LOST,refundCallbackWorker.processLease(old));
        assertEquals("PENDING",refundCallbackState(tenant,number)); assertApplicationAmounts(f,100,0);
        assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.REVIEW),refundCallbackWorker.runBatch(tenant,"new",1));
        assertEquals("REVIEW",refundCallbackState(tenant,number));
    }

    @Test void refundInboxForgedBusinessReferenceRollsBackUnrelatedRefund() {
        Fixture f=paymentFixture(2); long tenant=f.actor().tenantId();
        var order=paidRefundOrder(f,"two-refunds",2); refundPolicies.configure(f.actor(),"policy","CNY",200,0);
        var first=prepareRefund(f,requestRefund(f,order,"first",50));
        var second=prepareRefund(f,requestRefund(f,order,"second",50));
        String one=refundInbox.receive(tenant,refundEvidence(first,"first",50,"CNY",first.providerTransactionNo()));
        String two=refundInbox.receive(tenant,refundEvidence(second,"second",50,"CNY",second.providerTransactionNo()));
        assertCode("REFUND_CALLBACK_FACT_CONFLICT",()->refundInbox.receive(tenant,refundEvidence(second,"first",50,"CNY",second.providerTransactionNo())));
        var lease=jobLeases.claim(tenant,JobType.REFUND_CALLBACK,"worker",1,30000).getFirst();
        String other=lease.businessKey().equals(one)?two:one;
        var forged=new com.lrj.trade.jobs.model.JobLease(lease.jobId(),tenant,lease.jobType(),other,
            lease.owner(),lease.fencingToken(),lease.attempts(),lease.leaseUntil());
        assertEquals(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.LEASE_LOST,refundCallbackWorker.processLease(forged));
        assertEquals("PENDING",refundCallbackState(tenant,other)); assertApplicationAmounts(f,100,0);
        assertEquals(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.DONE,refundCallbackWorker.processLease(lease));
        assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.DONE),refundCallbackWorker.runBatch(tenant,"other",1));
        assertApplicationAmounts(f,0,100);
    }

    @Test void refundInboxValidChannelOutcomeRequiresActualDurableCommit() {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        var adapter=mock(com.lrj.trade.refund.RefundChannelAdapter.class);
        when(adapter.channelCode()).thenReturn(plan.channelCode());
        var proof=refundEvidence(plan,"network",100,"CNY",plan.providerTransactionNo());
        when(adapter.execute(any(),any(),any())).thenReturn(new com.lrj.trade.refund.RefundChannelAdapter.Reply(
            com.lrj.trade.refund.RefundChannelAdapter.State.EVIDENCE,proof));
        var service=new com.lrj.trade.refund.RefundChannelService(List.of(adapter),List.of(refundInbox),channelBindings);
        var binding=channelBindings.resolve(tenant,Long.parseLong(plan.paymentAttemptId()));
        assertEquals(plan.channelCode(),binding.channelCode());
        assertEquals(plan.channelMerchantNo(),binding.channelMerchantNo());
        assertThrows(TradeException.class,()->channelBindings.resolve(tenant+999999,Long.parseLong(plan.paymentAttemptId())));
        // 冻结只阻止新支付选择，历史资金仍必须使用原引用，不能寻找其他ACTIVE配置。
        jdbc.update("UPDATE tc_merchant_channel SET status='FROZEN' WHERE tenant_id=? AND id=?",tenant,Long.parseLong(binding.channelId()));
        assertEquals(binding,channelBindings.resolve(tenant,Long.parseLong(plan.paymentAttemptId())));
        when(adapter.execute(any(),any(),any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(binding,call.getArgument(1));
            return new com.lrj.trade.refund.RefundChannelAdapter.Reply(com.lrj.trade.refund.RefundChannelAdapter.State.EVIDENCE,proof);
        });
        assertEquals(com.lrj.trade.refund.RefundChannelPort.Outcome.EVIDENCE_ACCEPTED,service.invoke(tenant,plan));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund_callback WHERE tenant_id=?",tenant)); assertApplicationAmounts(f,100,0);
        assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.DONE),refundCallbackWorker.runBatch(tenant,"network",1));
        assertApplicationAmounts(f,0,100);
    }

    @Test void refundInboxFactRehashCannotBypassCipherAuthenticationOrSqlChecks() {
        Fixture f=paymentFixture(1); var plan=refundInboxPlan(f); long tenant=f.actor().tenantId();
        var input=refundEvidence(plan,"rehash",100,"CNY",plan.providerTransactionNo());
        String number=refundInbox.receive(tenant,input);
        String forged=Checks.hash("refund-callback-v1",plan.merchantRefundNo(),"SUCCEEDED",plan.providerTransactionNo(),
            "provider-rehash",99L,"CNY",clock.instant());
        jdbc.update("UPDATE tc_refund_callback SET amount=99,fact_hash=? WHERE tenant_id=? AND callback_no=?",forged,tenant,number);
        assertEquals(List.of(com.lrj.trade.jobs.RefundCallbackWorker.Outcome.REVIEW),refundCallbackWorker.runBatch(tenant,"rehash",1));
        assertApplicationAmounts(f,100,0);
        assertMysqlConstraint(3819,()->jdbc.update(
            "UPDATE tc_refund_callback SET provider_refund_no=NULL WHERE tenant_id=? AND callback_no=?",tenant,number));
        assertMysqlConstraint(3819,()->jdbc.update(
            "UPDATE tc_refund_callback SET observation_kind='FAILED_FINAL',provider_refund_no=NULL,amount=NULL,currency=NULL,occurred_at=NULL WHERE tenant_id=? AND callback_no=?",tenant,number));
    }

    private com.lrj.trade.refund.RefundExecutionPort.Plan refundInboxPlan(Fixture f) {
        var order=paidRefundOrder(f,"inbox",1); refundPolicies.configure(f.actor(),"policy","CNY",100,0);
        return prepareRefund(f,requestRefund(f,order,"inbox",100));
    }
    private com.lrj.trade.refund.model.VerifiedRefundEvidence refundEvidence(com.lrj.trade.refund.RefundExecutionPort.Plan plan,
        String event,long amount,String currency,String original) {
        return new com.lrj.trade.refund.model.VerifiedRefundEvidence(plan.channelCode(),plan.channelMerchantNo(),event,
            plan.merchantRefundNo(),original,"provider-"+event,com.lrj.trade.refund.model.VerifiedRefundEvidence.Kind.SUCCEEDED,
            amount,currency,clock.instant(),"test-cert",new byte[]{1,2,3});
    }
    private String refundCallbackState(long tenant,String number) {
        return jdbc.queryForObject("SELECT status FROM tc_refund_callback WHERE tenant_id=? AND callback_no=?",String.class,tenant,number);
    }

    @Test void refundApplicationFreezesComponentsAndDefaultsToApproval() {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"application",1);
        var refund=requestRefund(f,order,"request",40);
        assertEquals("PENDING_APPROVAL",refund.status()); assertNotNull(refund.approvalRef()); assertEquals(0,refund.policyVersion());
        assertEquals(40,refund.items().getFirst().amount()); assertEquals(order.items().getFirst().itemId(),refund.items().getFirst().orderItemId());
        assertEquals("WAIT_APPROVAL",prepareRefund(f,refund).action()); assertApplicationAmounts(f,40,0);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund_item WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='refund.approval-requested.internal.v0'",f.actor().tenantId()));
    }

    @Test void refundApplicationComponentLimitRollsBackEvenWhenReceiptHasEnough() {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId();
        String product=catalog.create(f.actor(),"second","second","商品二","s-second",100,"CNY");
        catalog.publish(f.actor(),"second-publish",Long.parseLong(product),0);
        long second=Long.parseLong(catalog.list(tenant,0,20,true).stream().filter(p->p.productId().equals(product)).findFirst().orElseThrow().skuId());
        inventory.adjust(f.actor(),"second-stock",f.warehouse(),second,1,"测试库存");
        var quote=quotes.create(f.actor(),"quote-two",List.of(new QuoteSelection(Long.toString(f.sku()),1L),new QuoteSelection(Long.toString(second),1L)));
        var order=orders.create(f.actor(),"order-two",Long.parseLong(quote.quoteId()),f.warehouse(),shipping());
        receive(f,createPayment(f.actor(),"pay-two",order.orderNo(),"TEST_A","return"),"two",200,"CNY");
        requestRefund(f,order,"first",80);
        assertCode("REFUND_COMPONENT_EXCEEDED",()->requestRefund(f,order,"overflow",40));
        assertApplicationAmounts(f,80,0);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_refund_hold WHERE tenant_id=?",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_refund_item WHERE tenant_id=?",tenant));
        var other=order.items().get(1);
        refunds.request(f.actor(),"other-component",order.orderNo(),40,List.of(new com.lrj.trade.refund.model.RefundSelection("GOODS",other.itemId(),0,40)),"测试退款");
        assertApplicationAmounts(f,120,0);
    }

    @Test void refundApplicationRejectsWrongComponentsTotalsAndUnchargedFees() {
        Fixture f=paymentFixture(2); var one=paidRefundOrder(f,"allocation-one",1); var two=paidRefundOrder(f,"allocation-two",1);
        var valid=new com.lrj.trade.refund.model.RefundSelection("GOODS",one.items().getFirst().itemId(),0,40);
        assertCode("REFUND_ALLOCATION_INVALID",()->refunds.request(f.actor(),"sum",one.orderNo(),41,List.of(valid),"原因"));
        assertCode("REFUND_ALLOCATION_INVALID",()->refunds.request(f.actor(),"duplicate",one.orderNo(),80,List.of(valid,valid),"原因"));
        assertCode("REFUND_ALLOCATION_INVALID",()->refunds.request(f.actor(),"wrong-item",one.orderNo(),40,List.of(
            new com.lrj.trade.refund.model.RefundSelection("GOODS",two.items().getFirst().itemId(),0,40)),"原因"));
        for(String type:List.of("FREIGHT","TAX")) assertCode("REFUND_COMPONENT_EXCEEDED",()->refunds.request(f.actor(),type,one.orderNo(),1,
            List.of(new com.lrj.trade.refund.model.RefundSelection(type,null,0,1)),"未收取附加费不能退款"));
        assertCode("REFUND_ALLOCATION_INVALID",()->refunds.request(f.actor(),"excess-type",one.orderNo(),1,List.of(
            new com.lrj.trade.refund.model.RefundSelection("EXCESS",null,0,1)),"普通申请不能伪装补偿"));
        assertApplicationAmounts(f,0,0); assertEquals(0,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void refundApplicationPermanentReplayAndCanonicalItemsSurviveCommandCleanup() throws Exception {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"permanent",1);
        var results=parallel(20,()->requestRefund(f,order,"permanent",40));
        assertEquals(1,results.stream().map(com.lrj.trade.refund.model.RefundView::refundNo).distinct().count());
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",f.actor().tenantId());
        assertEquals(results.getFirst(),requestRefund(f,order,"permanent",40));
        assertCode("IDEMPOTENCY_CONFLICT",()->requestRefund(f,order,"permanent",41));
        assertApplicationAmounts(f,40,0);
    }

    @Test void refundApplicationTwentyConcurrentRequestsRespectBothBudgets() throws Exception {
        Fixture f=paymentFixture(100); var order=paidRefundOrder(f,"concurrent",100);
        refundPolicies.configure(f.actor(),"policy","CNY",10000,0); var sequence=new AtomicLong();
        var results=parallel(20,()->{
            try { requestRefund(f,order,"request-"+sequence.incrementAndGet(),1000); return true; }
            catch(TradeException expected) { assertEquals("REFUND_AMOUNT_EXCEEDED",expected.code()); return false; }
        });
        assertEquals(10,results.stream().filter(Boolean::booleanValue).count()); assertApplicationAmounts(f,10000,0);
        assertEquals(10,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=? AND status='READY'",f.actor().tenantId()));
    }

    @Test void refundApplicationPolicyUsesCumulativeBudgetAndFrozenVersion() {
        Fixture f=paymentFixture(2); var order=paidRefundOrder(f,"policy",2);
        var before=requestRefund(f,order,"before-policy",10);
        refundPolicies.configure(f.actor(),"policy","CNY",100,0);
        var small=requestRefund(f,order,"small",40); var large=requestRefund(f,order,"split",60);
        assertEquals("READY",small.status()); assertEquals(1,small.policyVersion());
        assertEquals("PENDING_APPROVAL",large.status()); assertEquals(1,large.policyVersion());
        assertEquals("PENDING_APPROVAL",refunds.read(f.actor(),before.refundNo()).status());
        refundPolicies.configure(f.actor(),"changed-policy","CNY",200,1);
        assertEquals(1,refundPolicies.configure(f.actor(),"policy","CNY",100,0).version());
        assertEquals(2,refundPolicies.read(f.actor().tenantId()).version());
        assertEquals("PENDING_APPROVAL",refunds.read(f.actor(),large.refundNo()).status());
        assertCode("VERSION_CONFLICT",()->refundPolicies.configure(f.actor(),"stale-policy","CNY",100,1));
    }

    @Test void refundApplicationApprovalEnforcesSeparationBindingAndOneDecision() {
        Fixture f=paymentFixture(1); var refund=requestRefund(f,paidRefundOrder(f,"approval",1),"request",100);
        assertCode("REFUND_APPROVAL_INVALID",()->new TransactionTemplate(transactions).execute(s->refunds.applyApproval(f.actor(),refund.refundNo(),refund.approvalRef(),1,true,"evidence")));
        var approver=new Actor(f.actor().tenantId(),"https://identity.test","approver");
        assertCode("REFUND_APPROVAL_INVALID",()->new TransactionTemplate(transactions).execute(s->refunds.applyApproval(approver,refund.refundNo(),"wrong",1,true,"evidence")));
        var approved=approveRefund(f,refund,true,1); assertEquals("READY",approved.status());
        assertEquals(approved,approveRefund(f,refund,true,1));
        assertCode("REFUND_APPROVAL_CONFLICT",()->approveRefund(f,refund,true,2));
        assertCode("REFUND_APPROVAL_CONFLICT",()->approveRefund(f,refund,false,1));
        assertEquals("CREATE",prepareRefund(f,refund).action());
        assertCode("REFUND_APPROVAL_CONFLICT",()->approveRefund(f,refund,false,2));
        assertApplicationAmounts(f,100,0);
    }

    @Test void refundApplicationRejectedAndReliableFailedAmountsCanBeReused() {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"release",1);
        var rejected=requestRefund(f,order,"rejected",100); assertEquals("REJECTED",approveRefund(f,rejected,false,1).status());
        assertApplicationAmounts(f,0,0); assertEquals("REJECTED",requestRefund(f,order,"rejected",100).status());
        var retry=requestRefund(f,order,"retry",100); approveRefund(f,retry,true,1); prepareRefund(f,retry);
        for(int i=0;i<2;i++) assertEquals("FAILED",new TransactionTemplate(transactions).execute(s->refunds.confirmFailed(f.actor().tenantId(),retry.refundNo(),"test:certified-failure")).status());
        assertApplicationAmounts(f,0,0); assertEquals("DONE",prepareRefund(f,retry).action());
        assertNotNull(requestRefund(f,order,"new-attempt",100));
    }

    @Test void refundApplicationExpiredApprovalReleasesOnlyBeforeDispatch() {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"expiry",1); var refund=requestRefund(f,order,"expiry",100);
        assertCode("REFUND_APPROVAL_INVALID",()->new TransactionTemplate(transactions).execute(s->refunds.expireApproval(f.actor().tenantId(),refund.refundNo())));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-08T08:00:00.123Z"));
        assertCode("REFUND_APPROVAL_INVALID",()->approveRefund(f,refund,true,1));
        for(int i=0;i<2;i++) assertEquals("EXPIRED",new TransactionTemplate(transactions).execute(s->refunds.expireApproval(f.actor().tenantId(),refund.refundNo())).status());
        assertApplicationAmounts(f,0,0); assertEquals("EXPIRED",requestRefund(f,order,"expiry",100).status());
        assertCode("REFUND_APPROVAL_INVALID",()->approveRefund(f,refund,true,1));
    }

    @Test void refundApplicationSuccessUpdatesFinancialEvidenceExactlyOnce() throws Exception {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"success-app",1);
        refundPolicies.configure(f.actor(),"policy","CNY",100,0);
        var facts=tradeFacts.read(f.actor().tenantId(),Long.parseLong(members.me(f.actor()).memberId())); var stock=balance(f);
        var one=requestRefund(f,order,"partial-one",40); var plan=prepareRefund(f,one);
        assertEquals("CREATE",plan.action()); var queried=prepareRefund(f,one);
        assertEquals("QUERY",queried.action()); assertEquals(plan.merchantRefundNo(),queried.merchantRefundNo());
        parallel(20,()->completeRefund(f,one,"provider-one"));
        assertApplicationAmounts(f,0,40); assertEquals("PARTIALLY_REFUNDED",orders.read(f.actor(),order.orderNo()).financialStatus());
        var version=orders.read(f.actor(),order.orderNo()).version(); completeRefund(f,one,"provider-one");
        assertEquals(version,orders.read(f.actor(),order.orderNo()).version());
        var two=requestRefund(f,order,"partial-two",60); prepareRefund(f,two); completeRefund(f,two,"provider-two");
        assertApplicationAmounts(f,0,100);
        assertEquals("REFUNDED",orders.read(f.actor(),order.orderNo()).financialStatus());
        assertEquals("PAID",orders.read(f.actor(),order.orderNo()).status()); assertEquals(stock,balance(f));
        assertEquals(facts,tradeFacts.read(f.actor().tenantId(),Long.parseLong(members.me(f.actor()).memberId())));
        assertEquals(2,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",f.actor().tenantId()));
    }

    @Test void refundApplicationOuterFailureRollsBackRequestAndAllEvidence() {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"rollback-app",1); long version=orders.read(f.actor(),order.orderNo()).version();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(s->{
            requestRefund(f,order,"rollback",100); throw new IllegalStateException("下游失败，退款全部回滚");
        }));
        assertApplicationAmounts(f,0,0); assertEquals(version,orders.read(f.actor(),order.orderNo()).version());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_refund WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_refund_hold WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type LIKE 'refund.%'",f.actor().tenantId()));
    }

    @Test void refundApplicationStaleLeaseRollsBackFundsComponentsAndOrder() {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"stale-app",1); refundPolicies.configure(f.actor(),"policy","CNY",100,0);
        var refund=requestRefund(f,order,"refund",100); prepareRefund(f,refund);
        var old=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"test-stale",1,30000).getFirst(); expireLease(old);
        long version=orders.read(f.actor(),order.orderNo()).version();
        assertCode("JOB_LEASE_LOST",()->new TransactionTemplate(transactions).executeWithoutResult(s->{ completeRefund(f,refund,"provider"); jobLeases.complete(old); }));
        assertEquals("SUBMITTED",refunds.read(f.actor(),refund.refundNo()).status()); assertApplicationAmounts(f,100,0);
        assertEquals(version,orders.read(f.actor(),order.orderNo()).version());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",f.actor().tenantId()));
    }

    @Test void refundApplicationDatabaseRejectsMissingApprovalAndComponentOverflow() {
        Fixture f=paymentFixture(1); var refund=requestRefund(f,paidRefundOrder(f,"sql-app",1),"refund",50); long tenant=f.actor().tenantId();
        for(String status:List.of("READY","SUBMITTED","FAILED","REJECTED")) {
            var invalid=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE tc_refund SET status=? WHERE tenant_id=? AND refund_no=?",status,tenant,refund.refundNo()));
            assertEquals(3819,((java.sql.SQLException)invalid.getMostSpecificCause()).getErrorCode());
        }
        var overflow=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE tc_refund_component_budget SET refunded_amount=51 WHERE tenant_id=?",tenant));
        assertEquals(3819,((java.sql.SQLException)overflow.getMostSpecificCause()).getErrorCode()); assertApplicationAmounts(f,50,0);
    }

    @Test void refundAdminQueueIsBoundedTenantScopedAndReadOnly() throws Exception {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"queue",1);
        var first=requestRefund(f,order,"queue-one",40); var second=requestRefund(f,order,"queue-two",40);
        jwt("queue-admin","trade.admin",tenant); jwt("queue-member","",tenant); jwt("queue-other","trade.admin",tenant+999999);
        String path="/api/v1/trade/admin/refunds";
        assertEquals(401,request("GET",path,null,null).statusCode());
        assertEquals(403,request("GET",path,"queue-member",null).statusCode());
        assertEquals("[]",request("GET",path,"queue-other",null).body());
        var response=request("GET",path+"?limit=1","queue-admin",null); assertEquals(200,response.statusCode());
        var json=tools.jackson.databind.json.JsonMapper.builder().build(); var page=json.readTree(response.body());
        assertEquals(1,page.size()); assertEquals(first.refundNo(),page.get(0).path("refundNo").asText());
        String cursor=page.get(0).path("cursor").asText(); assertTrue(cursor.matches("[1-9][0-9]*"));
        var next=json.readTree(request("GET",path+"?after="+cursor,"queue-admin",null).body());
        assertEquals(1,next.size()); assertEquals(second.refundNo(),next.get(0).path("refundNo").asText());
        for(String bad:List.of("?limit=101","?limit=0","?after=-1","?status=UNKNOWN"))
            assertEquals(400,request("GET",path+bad,"queue-admin",null).statusCode());
        assertFalse(response.body().contains("applicantKey")); assertFalse(response.body().contains("reason"));
        assertFalse(response.body().contains("credentialRef"));
        assertEquals(80,refunds.listQueue(tenant,"PENDING_APPROVAL",0,100).stream().mapToLong(x->x.amount()).sum());
        assertApplicationAmounts(f,80,0);
        assertEquals("PENDING_APPROVAL",refunds.read(f.actor(),first.refundNo()).status());
        approveRefund(f,first,true,1);
        assertEquals(1,refunds.listQueue(tenant,"PENDING_APPROVAL",0,20).size());
        assertEquals(first.refundNo(),refunds.listQueue(tenant,"READY",0,20).getFirst().refundNo());
    }

    @Test void refundApplicationHttpEnforcesOwnerPolicyAdminAndNoSuccessEndpoint() throws Exception {
        Fixture f=paymentFixture(1); long tenant=f.actor().tenantId(); var order=paidRefundOrder(f,"http-app",1);
        jwt("tester","",tenant); jwt("refund-other","",tenant); jwt("refund-admin","trade.admin",tenant);
        members.register(new Actor(tenant,"https://identity.test","refund-other"),"其他会员");
        String policy="{\"currency\":\"CNY\",\"autoApproveLimit\":100,\"expectedVersion\":0}";
        assertEquals(403,request("PATCH","/api/v1/trade/admin/refund-policy","tester",policy,"policy").statusCode());
        assertEquals(200,request("PATCH","/api/v1/trade/admin/refund-policy","refund-admin",policy,"policy").statusCode());
        assertEquals(200,request("GET","/api/v1/trade/admin/refund-policy","refund-admin",null).statusCode());
        String path="/api/v1/trade/orders/"+order.orderNo()+"/refunds";
        String body="{\"amount\":40,\"items\":[{\"componentType\":\"GOODS\",\"orderItemId\":\""+order.items().getFirst().itemId()+"\",\"quantity\":0,\"amount\":40}],\"reason\":\"售后\"}";
        assertEquals(404,request("POST",path,"refund-other",body,"attack").statusCode());
        assertEquals(400,request("POST",path,"tester",body).statusCode());
        assertEquals(400,request("POST",path,"tester",body.replace("\"amount\":40,\"items\"","\"status\":\"SUCCEEDED\",\"amount\":40,\"items\""),"forged").statusCode());
        var response=request("POST",path,"tester",body,"refund"); assertEquals(202,response.statusCode(),response.body());
        var json=tools.jackson.databind.json.JsonMapper.builder().build(); String number=json.readTree(response.body()).path("refundNo").asText();
        assertEquals("READY",json.readTree(response.body()).path("status").asText());
        assertEquals(200,request("GET","/api/v1/trade/refunds/"+number,"tester",null).statusCode());
        assertEquals(404,request("GET","/api/v1/trade/refunds/"+number,"refund-other",null).statusCode());
        assertEquals(403,request("POST","/callbacks/v1/refunds/TEST_A","tester","{}","fake").statusCode());
        assertFalse(response.body().contains("providerTransactionNo")); assertFalse(response.body().contains("applicantKey"));
    }

    @Test void refundApplicationApprovalDecisionsRaceWithoutDoubleReleaseOrDispatch() throws Exception {
        Fixture f=paymentFixture(1); var refund=requestRefund(f,paidRefundOrder(f,"decision-race",1),"refund",100);
        var sequence=new AtomicLong();
        var results=parallel(2,()->{
            boolean approve=sequence.incrementAndGet()==1;
            try { return approveRefund(f,refund,approve,1).status(); }
            catch(TradeException conflict) { assertEquals("REFUND_APPROVAL_CONFLICT",conflict.code()); return "CONFLICT"; }
        });
        assertEquals(1,results.stream().filter("CONFLICT"::equals).count());
        boolean ready=refunds.read(f.actor(),refund.refundNo()).status().equals("READY");
        assertApplicationAmounts(f,ready?100:0,0); assertEquals(ready?"CREATE":"DONE",prepareRefund(f,refund).action());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action IN ('refund.approved','refund.rejected')",f.actor().tenantId()));
    }

    @Test void refundApplicationPermanentKeyCannotMoveOrderOrTenant() {
        Fixture f=paymentFixture(2); var one=paidRefundOrder(f,"owner-one",1); var two=paidRefundOrder(f,"owner-two",1);
        var refund=requestRefund(f,one,"same",30);
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",f.actor().tenantId());
        assertCode("IDEMPOTENCY_CONFLICT",()->requestRefund(f,two,"same",30));
        var other=new Actor(f.actor().tenantId(),"https://identity.test","other-refund-owner"); members.register(other,"其他人");
        assertCode("NOT_FOUND",()->refunds.read(other,refund.refundNo()));
        assertCode("NOT_FOUND",()->refunds.read(actor(),refund.refundNo()));
        assertCode("NOT_FOUND",()->refunds.request(other,"attack",one.orderNo(),30,List.of(
            new com.lrj.trade.refund.model.RefundSelection("GOODS",one.items().getFirst().itemId(),0,30)),"无权限"));
        assertApplicationAmounts(f,30,0);
    }

    @Test void refundApplicationPolicyCasAndCurrencyMismatchFailClosed() throws Exception {
        Fixture f=paymentFixture(1); var order=paidRefundOrder(f,"policy-cas",1); var sequence=new AtomicLong();
        var outcomes=parallel(2,()->{
            long id=sequence.incrementAndGet();
            try { refundPolicies.configure(f.actor(),"policy-"+id,"CNY",50*id,0); return true; }
            catch(TradeException conflict) { assertEquals("VERSION_CONFLICT",conflict.code()); return false; }
        });
        assertEquals(1,outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(1,refundPolicies.read(f.actor().tenantId()).version());
        refundPolicies.configure(f.actor(),"usd-policy","USD",100,1);
        assertEquals("PENDING_APPROVAL",requestRefund(f,order,"currency",20).status());
    }

    @Test void refundApplicationFrozenMemberKeepsAfterSaleButUnpaidOrderCannotRefund() {
        Fixture f=paymentFixture(1); var order=factOrder(f,"unpaid");
        assertCode("REFUND_NOT_PAID",()->requestRefund(f,order,"unpaid",40));
        receive(f,createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return"),"paid",100,"CNY");
        var member=members.me(f.actor());
        members.changeStatus(f.actor(),"freeze",Long.parseLong(member.memberId()),member.version(),"FROZEN","测试售后");
        var refund=requestRefund(f,order,"frozen",40); approveRefund(f,refund,true,1); prepareRefund(f,refund); completeRefund(f,refund,"provider");
        assertApplicationAmounts(f,0,40); assertEquals("FROZEN",members.me(f.actor()).status());
    }

    @Test void refundApplicationApprovalAndCreatePermissionRollbackWithCaller() {
        Fixture f=paymentFixture(1); var refund=requestRefund(f,paidRefundOrder(f,"permission-rollback",1),"refund",100);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->refunds.prepare(f.actor().tenantId(),refund.refundNo()));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(s->{approveRefund(f,refund,true,1); throw new IllegalStateException("审批后续失败");}));
        assertEquals("PENDING_APPROVAL",refunds.read(f.actor(),refund.refundNo()).status());
        approveRefund(f,refund,true,1);
        var old=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"test-create-lease",1,30000).getFirst(); expireLease(old);
        assertCode("JOB_LEASE_LOST",()->new TransactionTemplate(transactions).executeWithoutResult(s->{refunds.prepare(f.actor().tenantId(),refund.refundNo()); jobLeases.complete(old);}));
        assertEquals("READY",refunds.read(f.actor(),refund.refundNo()).status());
        assertEquals("HELD",jdbc.queryForObject("SELECT state FROM tc_payment_refund_hold WHERE tenant_id=? AND refund_no=?",String.class,f.actor().tenantId(),refund.refundNo()));
        assertApplicationAmounts(f,100,0);
    }

    private com.lrj.trade.order.model.OrderView paidRefundOrder(Fixture f,String key,long quantity) {
        var receipt=refundReceipt(f,key,quantity);
        String no=jdbc.queryForObject("SELECT order_no FROM tc_order WHERE tenant_id=? AND id=?",String.class,f.actor().tenantId(),Long.parseLong(receipt.orderId()));
        return orders.read(f.actor(),no);
    }
    private com.lrj.trade.refund.model.RefundView requestRefund(Fixture f,com.lrj.trade.order.model.OrderView order,String key,long amount) {
        return refunds.request(f.actor(),key,order.orderNo(),amount,List.of(new com.lrj.trade.refund.model.RefundSelection("GOODS",order.items().getFirst().itemId(),0,amount)),"测试退款");
    }
    private com.lrj.trade.refund.model.RefundView approveRefund(Fixture f,com.lrj.trade.refund.model.RefundView refund,boolean approve,long version) {
        return new TransactionTemplate(transactions).execute(s->refunds.applyApproval(new Actor(f.actor().tenantId(),"https://identity.test","approver"),refund.refundNo(),refund.approvalRef(),version,approve,"test:approval"));
    }
    private com.lrj.trade.refund.RefundExecutionPort.Plan prepareRefund(Fixture f,com.lrj.trade.refund.model.RefundView refund) {
        return new TransactionTemplate(transactions).execute(s->refunds.prepare(f.actor().tenantId(),refund.refundNo()));
    }
    private com.lrj.trade.refund.model.RefundView completeRefund(Fixture f,com.lrj.trade.refund.model.RefundView refund,String provider) {
        return new TransactionTemplate(transactions).execute(s->refunds.confirmSucceeded(f.actor().tenantId(),refund.refundNo(),provider,refund.amount(),refund.currency(),clock.instant(),"test:durable-refund"));
    }
    private void assertApplicationAmounts(Fixture f,long reserved,long refunded) {
        long tenant=f.actor().tenantId();
        assertEquals(reserved,count("SELECT COALESCE(SUM(refund_reserved_amount),0) FROM tc_payment_receipt WHERE tenant_id=?",tenant));
        assertEquals(refunded,count("SELECT COALESCE(SUM(refunded_amount),0) FROM tc_payment_receipt WHERE tenant_id=?",tenant));
        assertEquals(reserved,count("SELECT COALESCE(SUM(reserved_amount),0) FROM tc_refund_component_budget WHERE tenant_id=?",tenant));
        assertEquals(refunded,count("SELECT COALESCE(SUM(refunded_amount),0) FROM tc_refund_component_budget WHERE tenant_id=?",tenant));
    }

    @Test void refundFundsConcurrentReservationsCannotExceedRealReceipt() throws Exception {
        Fixture f=paymentFixture(100); var receipt=refundReceipt(f,"budget",100);
        var sequence=new AtomicLong();
        var results=parallel(20,()->{
            String refund="budget-"+sequence.incrementAndGet();
            try { reserveRefund(f,receipt,refund,1000); return true; }
            catch(TradeException failure) { assertEquals("REFUND_AMOUNT_EXCEEDED",failure.code()); return false; }
        });
        assertEquals(10,results.stream().filter(Boolean::booleanValue).count());
        assertEquals(10,count("SELECT COUNT(*) FROM tc_payment_refund_hold WHERE tenant_id=?",f.actor().tenantId()));
        assertRefundAmounts(f,receipt,10000,0);
    }

    @Test void refundFundsPermanentReplayAndBindingsSurviveCommandDeletion() throws Exception {
        Fixture f=paymentFixture(3); var receipt=refundReceipt(f,"replay",1);
        var holds=parallel(20,()->reserveRefund(f,receipt,"same",40));
        assertEquals(1,holds.stream().map(com.lrj.trade.payment.model.RefundHold::reservationNo).distinct().count());
        jdbc.update("DELETE FROM tc_command WHERE tenant_id=?",f.actor().tenantId());
        assertEquals(holds.getFirst(),reserveRefund(f,receipt,"same",40));
        assertCode("REFUND_BINDING_CONFLICT",()->reserveRefund(f,receipt,"same",41));
        var other=refundReceipt(f,"other",1);
        assertCode("REFUND_BINDING_CONFLICT",()->reserveRefund(f,other,"same",40));
        assertCode("REFUND_BINDING_CONFLICT",()->new TransactionTemplate(transactions).execute(s->
            refundFunds.reserve(f.actor().tenantId(),"wrong-order",receipt.receiptNo(),Long.parseLong(other.orderId()),10,"CNY")));
        assertCode("REFUND_BINDING_CONFLICT",()->new TransactionTemplate(transactions).execute(s->
            refundFunds.reserve(f.actor().tenantId(),"wrong-currency",receipt.receiptNo(),Long.parseLong(receipt.orderId()),10,"USD")));
        assertCode("NOT_FOUND",()->new TransactionTemplate(transactions).execute(s->refundFunds.lockSource(actor().tenantId(),receipt.receiptNo())));
        assertRefundAmounts(f,receipt,40,0); assertRefundAmounts(f,other,0,0);
    }

    @Test void refundFundsRequireOuterTransactionAndRollbackAllEffects() {
        Fixture f=paymentFixture(1); var receipt=refundReceipt(f,"rollback",1); long tenant=f.actor().tenantId();
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
            ()->refundFunds.reserve(tenant,"rollback",receipt.receiptNo(),Long.parseLong(receipt.orderId()),40,"CNY"));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(s->{
            refundFunds.reserve(tenant,"rollback",receipt.receiptNo(),Long.parseLong(receipt.orderId()),40,"CNY");
            refundFunds.markSubmitted(tenant,"rollback");
            refundFunds.confirmSucceeded(tenant,"rollback","provider",40,"CNY",clock.instant(),"test:success");
            throw new IllegalStateException("后续应用状态失败，应整体回滚");
        }));
        assertRefundAmounts(f,receipt,0,0);
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_refund_hold WHERE tenant_id=?",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type LIKE 'payment.refund-%'",tenant));
    }

    @Test void refundFundsUnknownKeepsBudgetAndOnlyReliableFailureReleasesOnce() {
        Fixture f=paymentFixture(1); var receipt=refundReceipt(f,"unknown",1); long tenant=f.actor().tenantId();
        reserveRefund(f,receipt,"unknown",100);
        var submitted=new TransactionTemplate(transactions).execute(s->refundFunds.markSubmitted(tenant,"unknown"));
        assertNotNull(submitted.submittedAt());
        assertCode("REFUND_TERMINAL_CONFLICT",()->new TransactionTemplate(transactions).execute(s->refundFunds.releaseUnsubmitted(tenant,"unknown","test:timeout")));
        assertCode("REFUND_AMOUNT_EXCEEDED",()->reserveRefund(f,receipt,"replacement",1));
        assertEquals("SUBMITTED",reserveRefund(f,receipt,"unknown",100).state());
        assertRefundAmounts(f,receipt,100,0);
        for(int i=0;i<2;i++) assertEquals("RELEASED",new TransactionTemplate(transactions).execute(s->
            refundFunds.releaseVerifiedFailure(tenant,"unknown","test:certified-terminal")).state());
        assertRefundAmounts(f,receipt,0,0);
        assertEquals("RELEASED",reserveRefund(f,receipt,"unknown",100).state());
        assertCode("REFUND_TERMINAL_CONFLICT",()->new TransactionTemplate(transactions).execute(s->refundFunds.markSubmitted(tenant,"unknown")));
        assertCode("REFUND_TERMINAL_CONFLICT",()->confirmRefund(f,"unknown","provider",100,clock.instant()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='payment.refund-released.internal.v0'",tenant));
    }

    @Test void refundFundsSuccessIsUniqueAndDoesNotRestockOrEraseFirstPaid() throws Exception {
        Fixture f=paymentFixture(1); var receipt=refundReceipt(f,"success",1); long tenant=f.actor().tenantId();
        long member=Long.parseLong(members.me(f.actor()).memberId());
        var before=tradeFacts.read(tenant,member); var stock=balance(f);
        reserveRefund(f,receipt,"success",100);
        var at=Instant.parse("2026-09-07T08:00:00.123456Z");
        var results=parallel(20,()->confirmRefund(f,"success","provider",100,at));
        assertTrue(results.stream().allMatch(h->h.state().equals("SUCCEEDED")));
        assertEquals(Instant.parse("2026-09-07T08:00:00.123Z"),results.getFirst().providerSucceededAt());
        assertRefundAmounts(f,receipt,0,100);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='RECEIPT'",tenant));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='payment.refund-succeeded.internal.v0'",tenant));
        assertTrue(before.everPaid()); assertEquals(before,tradeFacts.read(tenant,member)); assertEquals(stock,balance(f));
        assertCode("REFUND_FACT_CONFLICT",()->confirmRefund(f,"success","provider",100,at.plusMillis(1)));
        assertCode("REFUND_FACT_CONFLICT",()->confirmRefund(f,"success","changed-provider",100,at));
        assertCode("REFUND_FACT_CONFLICT",()->confirmRefund(f,"success","provider",99,at));
        assertCode("REFUND_TERMINAL_CONFLICT",()->new TransactionTemplate(transactions).execute(s->refundFunds.releaseUnsubmitted(tenant,"success","test:reject")));
    }

    @Test void refundFundsProviderRefundNumberCannotFundTwoHolds() {
        Fixture f=paymentFixture(1); var receipt=refundReceipt(f,"provider-unique",1);
        reserveRefund(f,receipt,"one",50); reserveRefund(f,receipt,"two",50);
        confirmRefund(f,"one","one-provider-refund",50,clock.instant());
        assertCode("REFUND_FACT_CONFLICT",()->confirmRefund(f,"two","one-provider-refund",50,clock.instant()));
        assertRefundAmounts(f,receipt,50,50);
        assertEquals("HELD",reserveRefund(f,receipt,"two",50).state());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",f.actor().tenantId()));
    }

    @Test void refundFundsSubmitAndLocalReleaseSerialize() throws Exception {
        Fixture f=paymentFixture(1); var receipt=refundReceipt(f,"race",1); long tenant=f.actor().tenantId();
        reserveRefund(f,receipt,"race",100); var sequence=new AtomicLong();
        var outcomes=parallel(2,()->{
            boolean submit=sequence.incrementAndGet()==1;
            try { return new TransactionTemplate(transactions).execute(s->submit?refundFunds.markSubmitted(tenant,"race"):
                refundFunds.releaseUnsubmitted(tenant,"race","test:reject")).state(); }
            catch(TradeException expected) { assertEquals("REFUND_TERMINAL_CONFLICT",expected.code()); return "CONFLICT"; }
        });
        assertEquals(1,outcomes.stream().filter("CONFLICT"::equals).count());
        var hold=reserveRefund(f,receipt,"race",100);
        assertRefundAmounts(f,receipt,hold.state().equals("SUBMITTED")?100:0,0);
    }

    @Test void refundFundsDifferentOrderFirstReservationsDoNotGapLockEachOther() throws Exception {
        Fixture f=paymentFixture(12);
        var receipts=new ArrayList<com.lrj.trade.payment.model.PaymentReceipt>();
        for(int i=0;i<12;i++) receipts.add(refundReceipt(f,"independent-"+i,1));
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        var results=parallel(12,()->{
            int index=sequence.getAndIncrement(); return reserveRefund(f,receipts.get(index),"independent-"+index,100);
        });
        assertEquals(12,results.stream().map(com.lrj.trade.payment.model.RefundHold::reservationNo).distinct().count());
        for(var receipt:receipts) assertRefundAmounts(f,receipt,100,0);
    }

    @Test void refundFundsDatabaseRejectsIncompleteSuccessAndWrongLedgerSource() {
        Fixture f=paymentFixture(2); var one=refundReceipt(f,"constraint-one",1); var two=refundReceipt(f,"constraint-two",1);
        reserveRefund(f,one,"constraint",50); long tenant=f.actor().tenantId();
        var incomplete=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE tc_payment_refund_hold SET state='SUCCEEDED' WHERE tenant_id=? AND refund_no='constraint'",tenant));
        assertEquals(3819,((java.sql.SQLException)incomplete.getMostSpecificCause()).getErrorCode());
        confirmRefund(f,"constraint","provider",50,clock.instant());
        var wrong=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE tc_money_ledger SET receipt_id=? WHERE tenant_id=? AND entry_type='REFUND'",Long.parseLong(two.receiptId()),tenant));
        assertEquals(1452,((java.sql.SQLException)wrong.getMostSpecificCause()).getErrorCode());
        assertRefundAmounts(f,one,0,50); assertRefundAmounts(f,two,0,0);
    }

    @Test void refundFundsStaleLeaseRollsBackSuccessButKeepsCommittedHold() {
        Fixture f=paymentFixture(1); var receipt=refundReceipt(f,"lease",1); long tenant=f.actor().tenantId();
        reserveRefund(f,receipt,"lease",100);
        new TransactionTemplate(transactions).executeWithoutResult(s->refundFunds.markSubmitted(tenant,"lease"));
        var old=jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"refund-funds-test",1,30000).getFirst(); expireLease(old);
        assertCode("JOB_LEASE_LOST",()->new TransactionTemplate(transactions).executeWithoutResult(s->{
            refundFunds.confirmSucceeded(tenant,"lease","provider",100,"CNY",clock.instant(),"test:durable-reference");
            jobLeases.complete(old);
        }));
        assertEquals("SUBMITTED",reserveRefund(f,receipt,"lease",100).state()); assertRefundAmounts(f,receipt,100,0);
        assertEquals(0,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",tenant));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='payment.refund-succeeded.internal.v0'",tenant));
        // 这里只证明Port可与既有租约同事务回滚；完整退款Inbox必须另行保证原始成功证据已耐久接收。
    }

    @Test void refundFundsSubmittedSuccessAndReliableFailureSerialize() throws Exception {
        Fixture f=paymentFixture(1); var receipt=refundReceipt(f,"terminal-race",1); long tenant=f.actor().tenantId();
        reserveRefund(f,receipt,"terminal-race",100);
        new TransactionTemplate(transactions).executeWithoutResult(s->refundFunds.markSubmitted(tenant,"terminal-race"));
        var sequence=new AtomicLong();
        var outcomes=parallel(2,()->{
            boolean success=sequence.incrementAndGet()==1;
            try { return new TransactionTemplate(transactions).execute(s->success?
                refundFunds.confirmSucceeded(tenant,"terminal-race","provider",100,"CNY",clock.instant(),"test:success"):
                refundFunds.releaseVerifiedFailure(tenant,"terminal-race","test:terminal-failure")).state(); }
            catch(TradeException conflict) { assertEquals("REFUND_TERMINAL_CONFLICT",conflict.code()); return "CONFLICT"; }
        });
        assertEquals(1,outcomes.stream().filter("CONFLICT"::equals).count());
        boolean succeeded=reserveRefund(f,receipt,"terminal-race",100).state().equals("SUCCEEDED");
        assertRefundAmounts(f,receipt,0,succeeded?100:0);
        assertEquals(succeeded?1:0,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",tenant));
    }

    @Test void refundFundsMixedPartialSuccessReleaseAndNewReservationConserveMoney() {
        Fixture f=paymentFixture(1); var receipt=refundReceipt(f,"mixed",1); long tenant=f.actor().tenantId();
        reserveRefund(f,receipt,"first",50); reserveRefund(f,receipt,"rejected",50);
        confirmRefund(f,"first","provider-one",50,clock.instant()); assertRefundAmounts(f,receipt,50,50);
        new TransactionTemplate(transactions).executeWithoutResult(s->refundFunds.releaseUnsubmitted(tenant,"rejected","test:reject"));
        assertRefundAmounts(f,receipt,0,50);
        assertEquals("RELEASED",reserveRefund(f,receipt,"rejected",50).state());
        reserveRefund(f,receipt,"replacement",50); assertRefundAmounts(f,receipt,50,50);
        confirmRefund(f,"replacement","provider-two",50,clock.instant()); assertRefundAmounts(f,receipt,0,100);
        assertCode("REFUND_AMOUNT_EXCEEDED",()->reserveRefund(f,receipt,"overflow",1));
        assertEquals(100,count("SELECT SUM(amount) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",tenant));
    }

    @Test void refundFundsConcurrentCrossOrderProviderNumberHasOnlyOneOwner() throws Exception {
        Fixture f=paymentFixture(2); var one=refundReceipt(f,"unique-one",1); var two=refundReceipt(f,"unique-two",1);
        reserveRefund(f,one,"one",100); reserveRefund(f,two,"two",100); var sequence=new AtomicLong();
        var outcomes=parallel(2,()->{
            String refund=sequence.incrementAndGet()==1?"one":"two";
            try { confirmRefund(f,refund,"same-provider",100,clock.instant()); return true; }
            catch(TradeException conflict) { assertEquals("REFUND_FACT_CONFLICT",conflict.code()); return false; }
        });
        assertEquals(1,outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(100,count("SELECT SUM(refunded_amount) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(100,count("SELECT SUM(refund_reserved_amount) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'",f.actor().tenantId()));
    }

    @Test void refundFundsAbnormalReceiptRemainsSeparateFromNormalPaidFacts() {
        Fixture f=paymentFixture(1); var order=factOrder(f,"abnormal");
        var p=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var normal=receive(f,p,"normal",100,"CNY");
        var abnormal=receive(f,p,"extra",150,"CNY");
        assertNotEquals(normal.disposition(),abnormal.disposition());
        long tenant=f.actor().tenantId(),member=Long.parseLong(members.me(f.actor()).memberId());
        var facts=tradeFacts.read(tenant,member); var stock=balance(f);
        reserveRefund(f,abnormal,"compensation",150);
        confirmRefund(f,"compensation","refund-extra",150,clock.instant());
        assertRefundAmounts(f,abnormal,0,150); assertRefundAmounts(f,normal,0,0);
        assertEquals(facts,tradeFacts.read(tenant,member)); assertEquals(stock,balance(f));
    }

    private com.lrj.trade.payment.model.PaymentReceipt refundReceipt(Fixture f,String key,long quantity) {
        var quote=quotes.create(f.actor(),"quote-"+key,List.of(new QuoteSelection(Long.toString(f.sku()),quantity)));
        var order=orders.create(f.actor(),"order-"+key,Long.parseLong(quote.quoteId()),f.warehouse(),shipping());
        var payment=createPayment(f.actor(),"pay-"+key,order.orderNo(),"TEST_A","return");
        return receive(f,payment,"provider-"+key,100*quantity,"CNY");
    }
    private com.lrj.trade.payment.model.RefundHold reserveRefund(Fixture f,com.lrj.trade.payment.model.PaymentReceipt receipt,String refund,long amount) {
        return new TransactionTemplate(transactions).execute(s->refundFunds.reserve(f.actor().tenantId(),refund,receipt.receiptNo(),
            Long.parseLong(receipt.orderId()),amount,"CNY"));
    }
    private com.lrj.trade.payment.model.RefundHold confirmRefund(Fixture f,String refund,String provider,long amount,Instant at) {
        return new TransactionTemplate(transactions).execute(s->refundFunds.confirmSucceeded(f.actor().tenantId(),refund,provider,amount,"CNY",at,"test:verified-refund"));
    }
    private void assertRefundAmounts(Fixture f,com.lrj.trade.payment.model.PaymentReceipt receipt,long reserved,long refunded) {
        var source=new TransactionTemplate(transactions).execute(s->refundFunds.lockSource(f.actor().tenantId(),receipt.receiptNo()));
        assertEquals(reserved,source.reservedAmount()); assertEquals(refunded,source.refundedAmount());
    }

    @Test void riskMissingProviderNeverMarksSubmittedOrCallsChannel() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"risk-default");
        var p=payments.create(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        assertEquals("RISK",paymentDispatch.prepare(f.actor().tenantId(),p.activeAttemptNo()).action());
        var first=riskEvaluation(f.actor().tenantId(),p.activeAttemptNo());
        assertEquals(first.evaluationNo(),riskEvaluation(f.actor().tenantId(),p.activeAttemptNo()).evaluationNo());
        assertEquals(com.lrj.trade.payment.PaymentRiskPort.Action.WAIT,paymentRisks.evaluate(first).action());
        var worker=recoveryWith(plan->{ fail("无ALLOW不能调用渠道"); return null; });
        assertEquals(List.of(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.RETRY),worker.runBatch(f.actor().tenantId(),"risk",1));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_attempt WHERE tenant_id=? AND submitted_at IS NOT NULL",f.actor().tenantId()));
        assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_risk WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void riskAllowExpiresForReplayButNeverBlocksQueryOrRealFunds() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"risk-expiry");
        var p=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        assertEquals("CREATE",paymentDispatch.prepare(f.actor().tenantId(),p.activeAttemptNo()).action());
        Instant advanced=clock.instant().plusSeconds(61); when(clock.instant()).thenReturn(advanced);
        assertEquals("QUERY",paymentDispatch.prepare(f.actor().tenantId(),p.activeAttemptNo()).action());
        assertEquals("RISK",new TransactionTemplate(transactions).execute(s->
            paymentDispatch.prepareReplayInTransaction(f.actor().tenantId(),p.activeAttemptNo())).action());
        var e=riskEvaluation(f.actor().tenantId(),p.activeAttemptNo());
        new TransactionTemplate(transactions).executeWithoutResult(s->paymentRisks.acceptInTransaction(e,riskDecision(e,com.lrj.trade.payment.PaymentRiskPort.Action.DENY)));
        assertEquals("REVIEW",new TransactionTemplate(transactions).execute(s->
            paymentDispatch.prepareReplayInTransaction(f.actor().tenantId(),p.activeAttemptNo())).action());
        assertEquals("QUERY",paymentDispatch.prepare(f.actor().tenantId(),p.activeAttemptNo()).action());
        paymentObservations.received(f.actor().tenantId(),p.activeAttemptNo(),"risk-real",100,"CNY",clock.instant(),"trusted-risk-test");
        assertEquals("PAID",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(2,count("SELECT COUNT(*) FROM tc_payment_risk WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void riskDecisionIsImmutableBoundAndOldRoundsCannotOverwriteNewOnes() {
        Fixture f=paymentFixture(3); var p=payments.create(f.actor(),"pay",factOrder(f,"risk-binding").orderNo(),"TEST_A","return");
        var e=riskEvaluation(f.actor().tenantId(),p.activeAttemptNo());
        var good=riskDecision(e,com.lrj.trade.payment.PaymentRiskPort.Action.ALLOW);
        var wrong=new com.lrj.trade.payment.PaymentRiskPort.Decision(e.evaluationNo(),e.tenantId()+1,e.requestNo(),e.inputHash(),
            good.action(),good.decisionId(),good.ruleVersion(),good.modelVersion(),good.validUntil());
        assertCode("RISK_BINDING_CONFLICT",()->new TransactionTemplate(transactions).executeWithoutResult(s->paymentRisks.acceptInTransaction(e,wrong)));
        new TransactionTemplate(transactions).executeWithoutResult(s->paymentRisks.acceptInTransaction(e,good));
        new TransactionTemplate(transactions).executeWithoutResult(s->paymentRisks.acceptInTransaction(e,good));
        assertCode("RISK_DECISION_CONFLICT",()->new TransactionTemplate(transactions).executeWithoutResult(s->
            paymentRisks.acceptInTransaction(e,riskDecision(e,com.lrj.trade.payment.PaymentRiskPort.Action.DENY))));
        Instant advanced=clock.instant().plusSeconds(61); when(clock.instant()).thenReturn(advanced);
        var next=riskEvaluation(e.tenantId(),e.requestNo()); assertNotEquals(e.evaluationNo(),next.evaluationNo());
        assertCode("RISK_BINDING_CONFLICT",()->new TransactionTemplate(transactions).executeWithoutResult(s->paymentRisks.acceptInTransaction(e,good)));
        assertEquals("PENDING",riskEvaluation(e.tenantId(),e.requestNo()).state());
    }

    @Test void riskRejectsUnboundedValidityExpiredResponseAndNullDeadlineInDatabase() {
        Fixture f=paymentFixture(3); var p=payments.create(f.actor(),"pay",factOrder(f,"risk-validity").orderNo(),"TEST_A","return");
        var e=riskEvaluation(f.actor().tenantId(),p.activeAttemptNo());
        var excessive=new com.lrj.trade.payment.PaymentRiskPort.Decision(e.evaluationNo(),e.tenantId(),e.requestNo(),e.inputHash(),
            com.lrj.trade.payment.PaymentRiskPort.Action.ALLOW,"decision","rule","NONE",clock.instant().plusSeconds(61));
        assertCode("RISK_PROTOCOL_INVALID",()->new TransactionTemplate(transactions).executeWithoutResult(s->paymentRisks.acceptInTransaction(e,excessive)));
        var sqlFailure=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE tc_payment_risk SET state='ALLOW',decision_id='d',rule_version='v',model_version='NONE' WHERE tenant_id=?",e.tenantId()));
        assertEquals(3819,((java.sql.SQLException)sqlFailure.getMostSpecificCause()).getErrorCode());
        when(clock.instant()).thenReturn(e.deadline());
        assertCode("RISK_EXPIRED",()->new TransactionTemplate(transactions).executeWithoutResult(s->
            paymentRisks.acceptInTransaction(e,riskDecision(e,com.lrj.trade.payment.PaymentRiskPort.Action.ALLOW))));
        assertNotEquals(e.evaluationNo(),riskEvaluation(e.tenantId(),e.requestNo()).evaluationNo());
    }

    @Test void riskStaleLeaseRollsBackDecisionAndPointerForeignKeyRejectsAnotherAttempt() {
        Fixture f=paymentFixture(3); var p=payments.create(f.actor(),"pay",factOrder(f,"risk-stale").orderNo(),"TEST_A","return");
        var e=riskEvaluation(f.actor().tenantId(),p.activeAttemptNo());
        var lease=jobLeases.claim(e.tenantId(),JobType.PAYMENT_RECOVER,"old",1,30000).getFirst(); expireLease(lease);
        assertCode("JOB_LEASE_LOST",()->new TransactionTemplate(transactions).executeWithoutResult(s->{
            paymentRisks.acceptInTransaction(e,riskDecision(e,com.lrj.trade.payment.PaymentRiskPort.Action.ALLOW)); jobLeases.complete(lease);
        }));
        assertEquals("PENDING",riskEvaluation(e.tenantId(),e.requestNo()).state());
        var other=payments.create(f.actor(),"other",factOrder(f,"risk-other").orderNo(),"TEST_A","return");
        var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE tc_payment_attempt SET risk_evaluation_no=? WHERE tenant_id=? AND merchant_request_no=?",e.evaluationNo(),e.tenantId(),other.activeAttemptNo()));
        assertEquals(1452,((java.sql.SQLException)failure.getMostSpecificCause()).getErrorCode());
    }

    @Test void riskProviderRecoveryAfterManyRetriesUsesShortRecheckAndFinallyCreates() {
        Fixture f=paymentFixture(3); var p=payments.create(f.actor(),"pay",factOrder(f,"risk-long-retry").orderNo(),"TEST_A","return");
        var custom=riskWith((e,timeout)->{
            assertEquals(java.time.Duration.ofSeconds(3),timeout);
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
            return riskDecision(e,com.lrj.trade.payment.PaymentRiskPort.Action.ALLOW);
        });
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var worker=new com.lrj.trade.jobs.PaymentRecoveryWorker(jobLeases,paymentDispatch,(tenant,plan)->{
            assertEquals("CREATE",plan.action()); calls.incrementAndGet(); return com.lrj.trade.payment.PaymentChannelPort.Outcome.PENDING;
        },transactions,custom);
        for(int i=0;i<8;i++) {
            var prior=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"prior",1,30000).getFirst();
            new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.retry(prior,100,"RISK_WAIT"));
            makeRecoveryDue(f.actor().tenantId());
        }
        assertEquals(List.of(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.RETRY),worker.runBatch(f.actor().tenantId(),"risk",1));
        assertEquals(0,calls.get());
        assertTrue(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(3),next_run_at) FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER'",Long.class,f.actor().tenantId())<=1000000L);
        makeRecoveryDue(f.actor().tenantId()); worker.runBatch(f.actor().tenantId(),"risk",1); assertEquals(1,calls.get());
    }

    @Test void riskNetworkCancellationNeverReusesOldCreatePlan() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"risk-cancel"); var p=payments.create(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var custom=riskWith((e,timeout)->{
            orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消");
            return riskDecision(e,com.lrj.trade.payment.PaymentRiskPort.Action.ALLOW);
        });
        List<String> calls=new ArrayList<>();
        var worker=new com.lrj.trade.jobs.PaymentRecoveryWorker(jobLeases,paymentDispatch,(tenant,plan)->{
            calls.add(plan.action()); return com.lrj.trade.payment.PaymentChannelPort.Outcome.PENDING;
        },transactions,custom);
        worker.runBatch(f.actor().tenantId(),"risk",1); makeRecoveryDue(f.actor().tenantId()); worker.runBatch(f.actor().tenantId(),"risk",1);
        assertFalse(calls.contains("CREATE"));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_attempt WHERE tenant_id=? AND submitted_at IS NOT NULL",f.actor().tenantId()));
        assertEquals("CLOSING",orders.read(f.actor(),order.orderNo()).status());
    }

    @Test void riskConcurrentFirstEvaluationsDoNotShareGapLocksOrDuplicateRounds() throws Exception {
        List<com.lrj.trade.payment.model.PaymentView> views=new ArrayList<>(); List<Long> tenants=new ArrayList<>();
        for(int i=0;i<12;i++) {
            Fixture f=paymentFixture(1); tenants.add(f.actor().tenantId());
            views.add(payments.create(f.actor(),"pay",factOrder(f,"risk-parallel").orderNo(),"TEST_A","return"));
        }
        var index=new java.util.concurrent.atomic.AtomicInteger();
        var all=parallel(12,()->{ int i=index.getAndIncrement(); return riskEvaluation(tenants.get(i),views.get(i).activeAttemptNo()); });
        assertEquals(12,all.stream().map(com.lrj.trade.payment.model.RiskEvaluation::evaluationNo).distinct().count());
        var same=parallel(12,()->riskEvaluation(tenants.getFirst(),views.getFirst().activeAttemptNo()));
        assertEquals(1,same.stream().map(com.lrj.trade.payment.model.RiskEvaluation::evaluationNo).distinct().count());
    }

    @Test void riskWorkerTakeoverDuringEvaluationRollsBackResult() {
        Fixture f=paymentFixture(3); var p=payments.create(f.actor(),"pay",factOrder(f,"risk-takeover").orderNo(),"TEST_A","return");
        var lease=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"old",1,30000).getFirst();
        var custom=riskWith((e,t)->{
            expireLease(lease); assertEquals(1,jobLeases.claim(e.tenantId(),JobType.PAYMENT_RECOVER,"new",1,30000).size());
            return riskDecision(e,com.lrj.trade.payment.PaymentRiskPort.Action.ALLOW);
        });
        var worker=new com.lrj.trade.jobs.PaymentRecoveryWorker(jobLeases,paymentDispatch,(tenant,plan)->{
            fail("失效风险工作者不得调用渠道"); return null;
        },transactions,custom);
        assertEquals(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.LEASE_LOST,worker.processLease(lease));
        assertEquals("PENDING",riskEvaluation(f.actor().tenantId(),p.activeAttemptNo()).state());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_attempt WHERE tenant_id=? AND submitted_at IS NOT NULL",f.actor().tenantId()));
    }

    @Test void riskExpiryWhileWaitingForFencingLockRollsBackCreatePermission() throws Exception {
        Fixture f=paymentFixture(3); var p=createPayment(f.actor(),"pay",factOrder(f,"risk-fence-expiry").orderNo(),"TEST_A","return");
        var lease=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"worker",1,30000).getFirst();
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var fencing=new CountDownLatch(1);
        var target=org.springframework.test.util.AopTestUtils.<com.lrj.trade.jobs.JobLeaseService>getUltimateTargetObject(jobLeases);
        doAnswer(call->{ fencing.countDown(); return call.callRealMethod(); }).when(target).fenceAndRenew(eq(lease),eq(30000L));
        var worker=recoveryWith(plan->{ fail("等待中已过期的许可不能发渠道"); return null; });
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(s->{
                jdbc.queryForList("SELECT id FROM tc_job_lease WHERE tenant_id=? AND id=? FOR UPDATE",lease.tenantId(),lease.jobId());
                locked.countDown();
                try { assertTrue(release.await(8,TimeUnit.SECONDS)); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            try {
                assertTrue(locked.await(5,TimeUnit.SECONDS));
                var work=pool.submit(()->worker.processLease(lease));
                assertTrue(fencing.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->work.get(100,TimeUnit.MILLISECONDS));
                Instant expired=clock.instant().plusSeconds(60); when(clock.instant()).thenReturn(expired);
                release.countDown();
                assertEquals(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.RETRY,work.get(10,TimeUnit.SECONDS));
            } finally { release.countDown(); holder.get(10,TimeUnit.SECONDS); }
        }
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_attempt WHERE tenant_id=? AND submitted_at IS NOT NULL",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_risk WHERE tenant_id=?",f.actor().tenantId()));
    }
    private com.lrj.trade.payment.model.RiskEvaluation riskEvaluation(long tenant,String request) {
        return new TransactionTemplate(transactions).execute(s->paymentRisks.prepareInTransaction(tenant,request));
    }
    private com.lrj.trade.payment.PaymentRiskPort.Decision riskDecision(com.lrj.trade.payment.model.RiskEvaluation e,
        com.lrj.trade.payment.PaymentRiskPort.Action action) {
        return new com.lrj.trade.payment.PaymentRiskPort.Decision(e.evaluationNo(),e.tenantId(),e.requestNo(),e.inputHash(),
            action,"decision-"+e.evaluationNo(),"rule-v1","NONE",action==com.lrj.trade.payment.PaymentRiskPort.Action.ALLOW?clock.instant().plusSeconds(60):null);
    }
    private com.lrj.trade.payment.PaymentRiskService riskWith(com.lrj.trade.payment.PaymentRiskProvider provider) {
        return new com.lrj.trade.payment.PaymentRiskService(application.getBean(com.lrj.trade.payment.mapper.PaymentRiskMapper.class),
            application.getBean(com.lrj.trade.payment.mapper.PaymentMapper.class),application.getBean(com.lrj.trade.payment.mapper.ChannelMapper.class),
            orders,clock,List.of(provider));
    }

    @Test void tenantDiscoveryUsesTypedCursorAndExcludesOnlyTerminalTasks() {
        long done=TENANTS.incrementAndGet(),review=TENANTS.incrementAndGet(),future=TENANTS.incrementAndGet(),running=TENANTS.incrementAndGet();
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            jobLeases.enqueue(done,JobType.PAYMENT_CALLBACK,"done");
            jobLeases.enqueue(review,JobType.PAYMENT_CALLBACK,"review");
            jobLeases.schedule(future,JobType.PAYMENT_CALLBACK,"future",Instant.parse("2099-01-01T00:00:00Z"));
            jobLeases.enqueue(running,JobType.PAYMENT_CALLBACK,"running");
            jobLeases.enqueue(done,JobType.PAYMENT_RECOVER,"other-type");
        });
        var doneLease=jobLeases.claim(done,JobType.PAYMENT_CALLBACK,"test",1,30000).getFirst();
        var reviewLease=jobLeases.claim(review,JobType.PAYMENT_CALLBACK,"test",1,30000).getFirst();
        jobLeases.claim(running,JobType.PAYMENT_CALLBACK,"test",1,30000);
        new TransactionTemplate(transactions).executeWithoutResult(s->{ jobLeases.complete(doneLease); jobLeases.review(reviewLease,"TEST_REVIEW"); });
        assertEquals(OptionalLong.of(future),tenantDiscovery.next(JobType.PAYMENT_CALLBACK,done-1));
        assertEquals(OptionalLong.of(running),tenantDiscovery.next(JobType.PAYMENT_CALLBACK,future));
        assertTrue(tenantDiscovery.next(JobType.PAYMENT_CALLBACK,running).isEmpty());
        assertEquals(OptionalLong.of(done),tenantDiscovery.next(JobType.PAYMENT_RECOVER,done-1));
        assertTrue(jobLeases.claim(future,JobType.PAYMENT_CALLBACK,"early",1,30000).isEmpty());
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
    }

    @Test void tenantDiscoveryIsInternalBoundedIndexedAndCannotJoinOuterTransaction() {
        assertTrue(application.getBeansOfType(com.lrj.trade.jobs.WorkerRuntime.class).isEmpty());
        assertCode("INVALID_ARGUMENT",()->tenantDiscovery.next(null,0));
        assertCode("INVALID_ARGUMENT",()->tenantDiscovery.next(JobType.PAYMENT_RECOVER,-1));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
            ()->new TransactionTemplate(transactions).executeWithoutResult(s->tenantDiscovery.next(JobType.PAYMENT_RECOVER,0)));
        long tenant=TENANTS.incrementAndGet(); enqueueJob(tenant,"indexed-discovery");
        var statement=sqlSessions.getConfiguration().getMappedStatement("com.lrj.trade.jobs.mapper.JobLeaseMapper.nextTenant");
        var bound=statement.getBoundSql(Map.of("type","PAYMENT_RECOVER","after",tenant-1));
        var plan=jdbc.queryForMap("EXPLAIN "+bound.getSql(),"PAYMENT_RECOVER",tenant-1);
        assertEquals("idx_job_tenant_rotation",plan.get("key"));
        assertFalse(String.valueOf(plan.get("Extra")).toLowerCase(Locale.ROOT).contains("filesort"));
        assertEquals("range",plan.get("type"));
    }

    @Test void runtimeRotatesRealDatabaseLeasesAndCommitsEachTypeWithoutProcessingOtherTests() throws Exception {
        long tenant=TENANTS.incrementAndGet();
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            for(var type:JobType.values()) jobLeases.enqueue(tenant,type,"runtime-test");
        });
        // 测试限定本用例租户，内部仍调用真实发现SQL；不能让常驻线程消费其他用例遗留的业务任务。
        var scoped=mock(com.lrj.trade.jobs.JobTenantDiscovery.class);
        when(scoped.next(any(),anyLong())).thenAnswer(c->{
            long after=c.getArgument(1);
            var found=tenantDiscovery.next(c.getArgument(0),after==0?tenant-1:after);
            return found.isPresent() && found.getAsLong()==tenant?found:OptionalLong.empty();
        });
        var batches=new EnumMap<JobType,com.lrj.trade.jobs.WorkerRuntime.Batch>(JobType.class);
        var finished=new CountDownLatch(JobType.values().length);
        for(var type:JobType.values()) batches.put(type,(selected,owner)->{
            assertEquals(tenant,selected);
            var leases=jobLeases.claim(selected,type,owner,1,30000);
            for(var lease:leases) {
                new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.complete(lease));
                finished.countDown();
            }
            return leases.size();
        });
        try(var runtime=new com.lrj.trade.jobs.WorkerRuntime(scoped,batches,new com.lrj.trade.jobs.WorkerRuntime.Settings(1,1,1,1,1,1,1,1,50,1000))) {
            runtime.start(); assertTrue(finished.await(10,TimeUnit.SECONDS)); runtime.stop();
            assertEquals(JobType.values().length,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND status='DONE' AND fencing_token=1",tenant));
            for(var type:JobType.values()) assertEquals(1,runtime.statistics(type).handled());
        }
    }

    @Test void positiveAmountOrderCreationSchedulesExpiryAndEarlyDeliveryDoesNotCancel() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"resolve-scheduled");
        assertEquals(order.expiresAt(),jdbc.queryForObject(
            "SELECT next_run_at FROM tc_job_lease WHERE tenant_id=? AND job_type='ORDER_RESOLVE' AND business_key=?",
            java.sql.Timestamp.class,f.actor().tenantId(),order.orderNo()).toInstant());
        // 固定业务时钟故意早于主库时钟，提前投递也必须重检业务到期，不能仅凭任务到期取消。
        makeOrderResolutionDue(f.actor().tenantId());
        var lease=jobLeases.claim(f.actor().tenantId(),JobType.ORDER_RESOLVE,"worker",1,30000).getFirst();
        assertEquals(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.RETRY,orderResolver.processLease(lease));
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        assertTrue(jobLeases.claim(f.actor().tenantId(),JobType.ORDER_RESOLVE,"early",1,30000).isEmpty());
    }

    @Test void expiredOrderWithoutPaymentAttemptReleasesExactlyOnce() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"resolve-expired");
        when(clock.instant()).thenReturn(order.expiresAt());
        makeOrderResolutionDue(f.actor().tenantId());
        assertEquals(List.of(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.DONE),orderResolver.runBatch(f.actor().tenantId(),"worker",1));
        assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(0,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_attempt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
        new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.wake(f.actor().tenantId(),JobType.ORDER_RESOLVE,order.orderNo()));
        assertTrue(orderResolver.runBatch(f.actor().tenantId(),"again",1).isEmpty());
    }

    @Test void cancelledNeverDispatchedAttemptClosesLocallyWithoutChannelProof() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"resolve-unsubmitted");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消");
        assertEquals(List.of(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.DONE),orderResolver.runBatch(f.actor().tenantId(),"worker",1));
        assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status());
        assertEquals("DONE",paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo()).action());
        assertEquals("LOCAL_UNSUBMITTED_V1:"+payment.activeAttemptNo(),jdbc.queryForObject(
            "SELECT close_evidence_ref FROM tc_payment_attempt WHERE tenant_id=? AND merchant_request_no=?",String.class,
            f.actor().tenantId(),payment.activeAttemptNo()));
        assertEquals(0,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_callback WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void cancellationAfterOldPaymentTaskDoneStillFinishesOrderTask() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"resolve-old-done");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo());
        paymentObservations.closed(f.actor().tenantId(),payment.activeAttemptNo(),"certified-closed");
        assertEquals(List.of(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.DONE),recoveryWorker.runBatch(f.actor().tenantId(),"pay-worker",1));
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
        orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消");
        assertEquals(List.of(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.DONE),orderResolver.runBatch(f.actor().tenantId(),"order-worker",1));
        assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status());
        assertEquals("DONE",jdbc.queryForObject(
            "SELECT status FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER'",String.class,f.actor().tenantId()));
    }

    @Test void unresolvedDispatchedOrderKeepsStockWhileClosingChannelOutsideTransaction() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"resolve-unknown");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo());
        orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消");
        var adapter=new com.lrj.trade.payment.PaymentChannelAdapter() {
            @Override public String channelCode() { return "TEST_A"; }
            @Override public Reply execute(com.lrj.trade.payment.model.PaymentDispatch plan,java.time.Duration timeout) {
                assertEquals("CLOSE",plan.action()); assertEquals(payment.activeAttemptNo(),plan.requestNo());
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
                return new Reply(State.PENDING,null);
            }
        };
        var worker=new com.lrj.trade.jobs.OrderResolutionWorker(jobLeases,orderResolution,
            new com.lrj.trade.payment.PaymentChannelService(List.of(adapter),callbackInbox),transactions);
        assertEquals(List.of(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.RETRY),worker.runBatch(f.actor().tenantId(),"worker",1));
        assertEquals("CLOSING",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
    }

    @Test void runningWakeBeforeRetryAndWakeAfterRetryBothRemainImmediatelyRunnable() {
        long tenant=TENANTS.incrementAndGet();
        new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.wake(tenant,JobType.ORDER_RESOLVE,"protocol"));
        var first=jobLeases.claim(tenant,JobType.ORDER_RESOLVE,"first",1,30000).getFirst();
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            jobLeases.wake(tenant,JobType.ORDER_RESOLVE,"protocol");
            jobLeases.wake(tenant,JobType.ORDER_RESOLVE,"protocol");
        });
        new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.retry(first,3600000,"WAIT"));
        var second=jobLeases.claim(tenant,JobType.ORDER_RESOLVE,"second",1,30000).getFirst();
        assertTrue(second.fencingToken()>first.fencingToken());
        new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.retry(second,3600000,"WAIT"));
        assertTrue(jobLeases.claim(tenant,JobType.ORDER_RESOLVE,"early",1,30000).isEmpty());
        new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.wake(tenant,JobType.ORDER_RESOLVE,"protocol"));
        var third=jobLeases.claim(tenant,JobType.ORDER_RESOLVE,"third",1,30000).getFirst();
        assertCode("JOB_LEASE_LOST",()->new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.complete(first)));
        new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.complete(third));
        new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.wake(tenant,JobType.ORDER_RESOLVE,"protocol"));
        assertTrue(jobLeases.claim(tenant,JobType.ORDER_RESOLVE,"terminal",1,30000).isEmpty());
    }

    @Test void expiredResolverLeaseRollsBackOrderAndStockRelease() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"resolve-stale");
        when(clock.instant()).thenReturn(order.expiresAt());
        makeOrderResolutionDue(f.actor().tenantId());
        var old=jobLeases.claim(f.actor().tenantId(),JobType.ORDER_RESOLVE,"old",1,30000).getFirst();
        expireLease(old);
        var current=jobLeases.claim(f.actor().tenantId(),JobType.ORDER_RESOLVE,"new",1,30000).getFirst();
        assertEquals(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.LEASE_LOST,orderResolver.processLease(old));
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        assertEquals(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.DONE,orderResolver.processLease(current));
        assertEquals(0,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
    }

    @Test void orderScheduleAndCancellationWakeFailuresRollBackBusiness() {
        Fixture f=paymentFixture(4);
        var target=org.springframework.test.util.AopTestUtils.<com.lrj.trade.jobs.JobLeaseService>getUltimateTargetObject(jobLeases);
        doThrow(new IllegalStateException("测试预约失败")).doCallRealMethod().when(target)
            .schedule(eq(f.actor().tenantId()),eq(JobType.ORDER_RESOLVE),anyString(),any());
        assertThrows(IllegalStateException.class,()->factOrder(f,"schedule-fail"));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_order WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        var order=factOrder(f,"schedule-retry");
        doThrow(new IllegalStateException("测试唤醒失败")).doCallRealMethod().when(target)
            .wake(eq(f.actor().tenantId()),eq(JobType.ORDER_RESOLVE),eq(order.orderNo()));
        assertThrows(IllegalStateException.class,()->orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消"));
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消");
        assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status());
    }

    @Test void orderBackfillCoversNoPaymentOrdersAndDoesNotRestartTerminalJobs() {
        Fixture f=paymentFixture(4); var one=factOrder(f,"backfill-one"); var two=factOrder(f,"backfill-two");
        orders.cancel(f.actor(),"cancel",one.orderNo(),"用户取消");
        orderResolver.runBatch(f.actor().tenantId(),"done",10);
        jdbc.update("DELETE FROM tc_job_lease WHERE tenant_id=? AND job_type='ORDER_RESOLVE' AND business_key=?",f.actor().tenantId(),two.orderNo());
        long cursor=orderBackfill.enqueuePage(f.actor().tenantId(),0,1);
        long last=orderBackfill.enqueuePage(f.actor().tenantId(),cursor,1);
        assertTrue(last>cursor); assertEquals(last,orderBackfill.enqueuePage(f.actor().tenantId(),last,1));
        orderBackfill.enqueuePage(f.actor().tenantId(),0,100);
        assertEquals(2,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='ORDER_RESOLVE'",f.actor().tenantId()));
        assertEquals("DONE",jdbc.queryForObject(
            "SELECT status FROM tc_job_lease WHERE tenant_id=? AND job_type='ORDER_RESOLVE' AND business_key=?",String.class,f.actor().tenantId(),one.orderNo()));
        assertEquals(0,orderBackfill.enqueuePage(f.actor().tenantId()+100000,0,100));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
            ()->new TransactionTemplate(transactions).execute(s->orderBackfill.enqueuePage(f.actor().tenantId(),0,100)));
    }

    @Test void wakeRollbackPreservesScheduleAndGenerationConstraintIsEnforced() {
        long tenant=TENANTS.incrementAndGet(); Instant future=Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.schedule(tenant,JobType.ORDER_RESOLVE,"scheduled",future));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(s->{
            jobLeases.wake(tenant,JobType.ORDER_RESOLVE,"scheduled"); throw new IllegalStateException("业务回滚");
        }));
        assertEquals(future,jdbc.queryForObject("SELECT next_run_at FROM tc_job_lease WHERE tenant_id=?",java.sql.Timestamp.class,tenant).toInstant());
        assertTrue(jobLeases.claim(tenant,JobType.ORDER_RESOLVE,"early",1,30000).isEmpty());
        assertMysqlConstraint(3819,()->jdbc.update(
            "UPDATE tc_job_lease SET claimed_wake_generation=1 WHERE tenant_id=?",tenant));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->jobLeases.wake(tenant,JobType.ORDER_RESOLVE,"scheduled"));
    }


    @Test void concurrentWakeAndRetryCannotLoseAnyCommittedWake() throws Exception {
        long tenant=TENANTS.incrementAndGet();
        new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.wake(tenant,JobType.ORDER_RESOLVE,"race"));
        var lease=jobLeases.claim(tenant,JobType.ORDER_RESOLVE,"worker",1,30000).getFirst();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var wakes=pool.submit(()->{ start.await(); return parallel(20,()->{
                new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.wake(tenant,JobType.ORDER_RESOLVE,"race"));
                return true;
            }); });
            var retry=pool.submit(()->{ start.await();
                new TransactionTemplate(transactions).executeWithoutResult(s->jobLeases.retry(lease,3600000,"WAIT"));
                return true;
            });
            start.countDown(); assertEquals(20,wakes.get(30,TimeUnit.SECONDS).size()); assertTrue(retry.get(30,TimeUnit.SECONDS));
        }
        assertEquals(21,count("SELECT wake_generation FROM tc_job_lease WHERE tenant_id=?",tenant));
        assertEquals(1,jobLeases.claim(tenant,JobType.ORDER_RESOLVE,"next",1,30000).size());
    }

    @Test void realFundsReturnedDuringCloseAreDurableAndWinNormalOrderSettlement() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"resolve-paid-during-close");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo());
        orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消");
        var adapter=new com.lrj.trade.payment.PaymentChannelAdapter() {
            @Override public String channelCode() { return "TEST_A"; }
            @Override public Reply execute(com.lrj.trade.payment.model.PaymentDispatch plan,java.time.Duration timeout) {
                assertEquals("CLOSE",plan.action());
                return new Reply(State.EVIDENCE,verifiedCallback(f,payment,"close-found-paid","txn",100));
            }
        };
        var worker=new com.lrj.trade.jobs.OrderResolutionWorker(jobLeases,orderResolution,
            new com.lrj.trade.payment.PaymentChannelService(List.of(adapter),callbackInbox),transactions);
        assertEquals(List.of(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.RETRY),worker.runBatch(f.actor().tenantId(),"worker",1));
        assertEquals("CLOSING",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        callbackWorker.runBatch(f.actor().tenantId(),"callback",1);
        assertEquals("PAID",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(List.of(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.DONE),worker.runBatch(f.actor().tenantId(),"worker",1));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
    }


    @Test void createPermissionRacingCancellationNeverAllowsLocalReleaseAfterCreate() throws Exception {
        Fixture f=paymentFixture(3); var order=factOrder(f,"resolve-create-race");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var create=pool.submit(()->{ start.await(); return paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo()).action(); });
            var close=pool.submit(()->{ start.await(); orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消");
                return orderResolver.runBatch(f.actor().tenantId(),"worker",1); });
            start.countDown();
            String action=create.get(30,TimeUnit.SECONDS); close.get(30,TimeUnit.SECONDS);
            if("CREATE".equals(action)) {
                assertEquals("CLOSING",orders.read(f.actor(),order.orderNo()).status());
                assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
                assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM tc_payment_attempt WHERE tenant_id=?",String.class,f.actor().tenantId()));
            } else {
                assertTrue(Set.of("CLOSE","DONE").contains(action));
                assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status());
                assertEquals(0,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
            }
        }
    }

    @Test void staleLocalUnsubmittedCloseRollsBackAttemptIntentAndInventoryTogether() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"resolve-unsubmitted-stale");
        createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消");
        var old=jobLeases.claim(f.actor().tenantId(),JobType.ORDER_RESOLVE,"old",1,30000).getFirst();
        expireLease(old);
        var current=jobLeases.claim(f.actor().tenantId(),JobType.ORDER_RESOLVE,"new",1,30000).getFirst();
        assertEquals(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.LEASE_LOST,orderResolver.processLease(old));
        assertEquals("CLOSING",orders.read(f.actor(),order.orderNo()).status());
        assertEquals("PREPARED",jdbc.queryForObject("SELECT status FROM tc_payment_attempt WHERE tenant_id=?",String.class,f.actor().tenantId()));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM tc_payment_intent WHERE tenant_id=?",String.class,f.actor().tenantId()));
        assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='payment.attempt-closed.internal.v0'",f.actor().tenantId()));
        assertEquals(com.lrj.trade.jobs.OrderResolutionWorker.Outcome.DONE,orderResolver.processLease(current));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='payment.attempt-closed.internal.v0'",f.actor().tenantId()));
    }

    private void makeOrderResolutionDue(long tenant) {
        // 只在隔离数据库模拟队列到期，不依赖执行机器日期刚好晚于固定业务时钟。
        jdbc.update("UPDATE tc_job_lease SET next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND job_type='ORDER_RESOLVE' AND status='READY'",tenant);
    }

    @Autowired com.lrj.trade.jobs.PaymentRecoveryWorker recoveryWorker;

    @Test void recoveryUnknownRetriesSameRequestAndNetworkHoldsNoTransactionOrConnection() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"recovery-unknown");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        List<String> actions=new ArrayList<>();
        var worker=recoveryWith(plan->{
            assertEquals(payment.activeAttemptNo(),plan.requestNo());
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
            actions.add(plan.action());
            if(actions.size()==1) throw new com.lrj.trade.payment.ChannelCallUncertainException();
            return new com.lrj.trade.payment.PaymentChannelAdapter.Reply(com.lrj.trade.payment.PaymentChannelAdapter.State.PENDING,null);
        });
        assertEquals(List.of(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.RETRY),worker.runBatch(f.actor().tenantId(),"worker",1));
        assertTrue(worker.runBatch(f.actor().tenantId(),"worker",1).isEmpty());
        makeRecoveryDue(f.actor().tenantId());
        assertEquals(List.of(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.RETRY),worker.runBatch(f.actor().tenantId(),"worker",1));
        assertEquals(List.of("CREATE","QUERY"),actions);
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void recoveryDurableEvidenceIsProcessedSeparatelyBeforeTaskCompletes() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"recovery-evidence");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        AtomicLong calls=new AtomicLong();
        var worker=recoveryWith(plan->{
            calls.incrementAndGet();
            return new com.lrj.trade.payment.PaymentChannelAdapter.Reply(com.lrj.trade.payment.PaymentChannelAdapter.State.EVIDENCE,
                verifiedCallback(f,payment,"authenticated-query","txn",100));
        });
        assertEquals(List.of(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.RETRY),worker.runBatch(f.actor().tenantId(),"worker",1));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_callback WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        callbackWorker.runBatch(f.actor().tenantId(),"callback",1);
        makeRecoveryDue(f.actor().tenantId());
        assertEquals(List.of(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.DONE),worker.runBatch(f.actor().tenantId(),"worker",1));
        assertEquals(1,calls.get());
        assertEquals("PAID",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void staleRecoveryLeaseCannotCommitCreatePermissionOrCallChannel() {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"recovery-stale").orderNo(),"TEST_A","return");
        var old=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"old",1,30000).getFirst();
        expireLease(old);
        var current=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"new",1,30000).getFirst();
        List<String> actions=new ArrayList<>();
        var worker=recoveryWith(plan->{
            actions.add(plan.action());
            return new com.lrj.trade.payment.PaymentChannelAdapter.Reply(com.lrj.trade.payment.PaymentChannelAdapter.State.UNKNOWN,null);
        });
        assertEquals(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.LEASE_LOST,worker.processLease(old));
        assertTrue(actions.isEmpty());
        assertNull(jdbc.queryForObject("SELECT submitted_at FROM tc_payment_attempt WHERE tenant_id=? AND merchant_request_no=?",java.sql.Timestamp.class,
            f.actor().tenantId(),payment.activeAttemptNo()));
        assertEquals(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.RETRY,worker.processLease(current));
        assertEquals(List.of("CREATE"),actions);
    }

    @Test void recoveryLateNetworkEvidenceSurvivesLeaseTakeoverWithoutOverwritingNewOwner() {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"recovery-late").orderNo(),"TEST_A","return");
        var old=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"old",1,30000).getFirst();
        List<com.lrj.trade.jobs.model.JobLease> next=new ArrayList<>();
        var worker=recoveryWith(plan->{
            expireLease(old);
            next.add(jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"new",1,30000).getFirst());
            return new com.lrj.trade.payment.PaymentChannelAdapter.Reply(com.lrj.trade.payment.PaymentChannelAdapter.State.EVIDENCE,
                verifiedCallback(f,payment,"late-response","txn",100));
        });
        assertEquals(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.LEASE_LOST,worker.processLease(old));
        assertEquals("new",jdbc.queryForObject("SELECT owner FROM tc_job_lease WHERE id=?",String.class,old.jobId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_callback WHERE tenant_id=?",f.actor().tenantId()));
        callbackWorker.runBatch(f.actor().tenantId(),"callback",1);
        assertEquals(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.DONE,worker.processLease(next.getFirst()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void recoveryCloseUnknownRetainsStockUntilDurableReliableProofIsProcessed() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"recovery-close");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消");
        AtomicLong calls=new AtomicLong();
        var worker=recoveryWith(plan->{
            assertEquals("CLOSE",plan.action());
            if(calls.incrementAndGet()==1)
                return new com.lrj.trade.payment.PaymentChannelAdapter.Reply(com.lrj.trade.payment.PaymentChannelAdapter.State.PENDING,null);
            return new com.lrj.trade.payment.PaymentChannelAdapter.Reply(com.lrj.trade.payment.PaymentChannelAdapter.State.EVIDENCE,
                new com.lrj.trade.payment.model.VerifiedPaymentCallback("TEST_A","sandbox-"+f.actor().tenantId()+"-TEST_A","closed",
                    payment.activeAttemptNo(),"CLOSED",null,null,null,null,"test-cert-v1",new byte[]{1,2}));
        });
        worker.runBatch(f.actor().tenantId(),"worker",1);
        assertEquals("CLOSING",orders.read(f.actor(),order.orderNo()).status());
        makeRecoveryDue(f.actor().tenantId()); worker.runBatch(f.actor().tenantId(),"worker",1);
        assertEquals("CLOSING",orders.read(f.actor(),order.orderNo()).status());
        callbackWorker.runBatch(f.actor().tenantId(),"callback",1);
        assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void recoveryWithoutAdapterWaitsAndRejectsOuterTransactions() {
        Fixture f=paymentFixture(3); createPayment(f.actor(),"pay",factOrder(f,"recovery-missing").orderNo(),"TEST_A","return");
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
            ()->new TransactionTemplate(transactions).execute(s->recoveryWorker.runBatch(f.actor().tenantId(),"worker",1)));
        assertEquals(List.of(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.RETRY),recoveryWorker.runBatch(f.actor().tenantId(),"worker",1));
        assertEquals("CHANNEL_UNAVAILABLE",jdbc.queryForObject(
            "SELECT last_error FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER'",String.class,f.actor().tenantId()));
    }


    @Test void recoveryRecreatesSameRequestAfterCrashBeforeFirstNetworkCall() {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"recovery-before-call").orderNo(),"TEST_A","return");
        assertEquals("CREATE",paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo()).action());
        List<String> actions=new ArrayList<>();
        var worker=recoveryWith(plan->{
            assertEquals(payment.activeAttemptNo(),plan.requestNo()); actions.add(plan.action());
            return new com.lrj.trade.payment.PaymentChannelAdapter.Reply(
                plan.action().equals("QUERY")?com.lrj.trade.payment.PaymentChannelAdapter.State.REPLAYABLE_ABSENCE:
                    com.lrj.trade.payment.PaymentChannelAdapter.State.PENDING,null);
        });
        assertEquals(List.of(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.RETRY),worker.runBatch(f.actor().tenantId(),"worker",1));
        assertEquals(List.of("QUERY","CREATE"),actions);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_attempt WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void recoveryRechecksCancellationAfterAbsenceBeforeSafeReplay() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"recovery-cancel-replay");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo());
        List<String> actions=new ArrayList<>();
        var worker=recoveryWith(plan->{
            actions.add(plan.action());
            if(plan.action().equals("QUERY")) {
                orders.cancel(f.actor(),"cancel",order.orderNo(),"用户取消");
                return new com.lrj.trade.payment.PaymentChannelAdapter.Reply(com.lrj.trade.payment.PaymentChannelAdapter.State.REPLAYABLE_ABSENCE,null);
            }
            return new com.lrj.trade.payment.PaymentChannelAdapter.Reply(com.lrj.trade.payment.PaymentChannelAdapter.State.PENDING,null);
        });
        worker.runBatch(f.actor().tenantId(),"worker",1);
        assertEquals(List.of("QUERY","CLOSE"),actions);
        assertEquals("CLOSING",orders.read(f.actor(),order.orderNo()).status());
    }


    @Test void recoveryTakeoverBetweenQueryAndReplayPreventsSecondNetworkCall() {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"recovery-replay-stale").orderNo(),"TEST_A","return");
        paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo());
        var old=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"old",1,30000).getFirst();
        List<String> actions=new ArrayList<>();
        var worker=recoveryWith(plan->{
            actions.add(plan.action());
            expireLease(old);
            assertEquals(1,jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"new",1,30000).size());
            return new com.lrj.trade.payment.PaymentChannelAdapter.Reply(com.lrj.trade.payment.PaymentChannelAdapter.State.REPLAYABLE_ABSENCE,null);
        });
        assertEquals(com.lrj.trade.jobs.PaymentRecoveryWorker.Outcome.LEASE_LOST,worker.processLease(old));
        assertEquals(List.of("QUERY"),actions);
        assertEquals("new",jdbc.queryForObject("SELECT owner FROM tc_job_lease WHERE id=?",String.class,old.jobId()));
    }

    private com.lrj.trade.jobs.PaymentRecoveryWorker recoveryWith(
        java.util.function.Function<com.lrj.trade.payment.model.PaymentDispatch,com.lrj.trade.payment.PaymentChannelAdapter.Reply> behavior) {
        var adapter=new com.lrj.trade.payment.PaymentChannelAdapter() {
            @Override public String channelCode() { return "TEST_A"; }
            @Override public boolean supportsSafeCreateReplay() { return true; }
            @Override public Reply execute(com.lrj.trade.payment.model.PaymentDispatch plan,java.time.Duration timeout) {
                assertEquals(java.time.Duration.ofSeconds(10),timeout);
                return behavior.apply(plan);
            }
        };
        var channel=new com.lrj.trade.payment.PaymentChannelService(List.of(adapter),callbackInbox);
        return new com.lrj.trade.jobs.PaymentRecoveryWorker(jobLeases,paymentDispatch,channel,transactions,paymentRisks);
    }

    private void makeRecoveryDue(long tenant) {
        jdbc.update("UPDATE tc_job_lease SET next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND job_type='PAYMENT_RECOVER' AND status='READY'",tenant);
    }

    @Test void callbackReceiptIsDurableEncryptedAndRepeatedEventsSettleOnlyOnce() throws Exception {
        doAnswer(call->{
            // 加密阶段不得持有此前Mapper查询绑定的连接，避免并发时一请求占两连接。
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
            return call.callRealMethod();
        }).when(callbackProtector).encrypt(anyString(),any(byte[].class));
        Fixture f=paymentFixture(4); var order=factOrder(f,"callback");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var input=verifiedCallback(f,payment,"event-one","txn-one",100);
        var received=parallel(20,()->callbackInbox.accept(f.actor().tenantId(),input));
        assertEquals(1,received.stream().distinct().count());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_callback WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        byte[] cipher=jdbc.queryForObject("SELECT payload_cipher FROM tc_payment_callback WHERE tenant_id=?",byte[].class,f.actor().tenantId());
        assertEquals(input.payload().length+28,cipher.length); assertFalse(Arrays.equals(input.payload(),cipher));
        AtomicLong events=new AtomicLong();
        parallel(100,()->callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,payment,"event-"+events.incrementAndGet(),"txn-one",100)));
        assertEquals(101,count("SELECT COUNT(*) FROM tc_payment_callback WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(100,callbackWorker.runBatch(f.actor().tenantId(),"callback-worker",100).size());
        assertEquals(List.of(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE),callbackWorker.runBatch(f.actor().tenantId(),"callback-worker",100));
        assertEquals("PAID",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(received.getFirst(),callbackInbox.accept(f.actor().tenantId(),input));
        assertTrue(callbackWorker.runBatch(f.actor().tenantId(),"callback-worker",100).isEmpty());
    }

    @Test void callbackEventConflictsAndMerchantMismatchNeverOverwriteOriginalEvidence() {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"conflict").orderNo(),"TEST_A","return");
        var input=verifiedCallback(f,payment,"same-event","txn",100);
        String number=callbackInbox.accept(f.actor().tenantId(),input);
        var wrongFacts=new com.lrj.trade.payment.model.VerifiedPaymentCallback(input.channelCode(),input.channelMerchantNo(),input.eventId(),
            input.requestNo(),input.kind(),input.transactionNo(),101L,input.currency(),input.providerPaidAt(),input.signatureVersion(),input.payload());
        assertCode("CALLBACK_FACT_CONFLICT",()->callbackInbox.accept(f.actor().tenantId(),wrongFacts));
        var wrongPayload=new com.lrj.trade.payment.model.VerifiedPaymentCallback(input.channelCode(),input.channelMerchantNo(),input.eventId(),
            input.requestNo(),input.kind(),input.transactionNo(),input.amount(),input.currency(),input.providerPaidAt(),input.signatureVersion(),new byte[]{1});
        assertCode("CALLBACK_FACT_CONFLICT",()->callbackInbox.accept(f.actor().tenantId(),wrongPayload));
        var wrongMerchant=new com.lrj.trade.payment.model.VerifiedPaymentCallback(input.channelCode(),"foreign-merchant",input.eventId(),
            input.requestNo(),input.kind(),input.transactionNo(),input.amount(),input.currency(),input.providerPaidAt(),input.signatureVersion(),input.payload());
        assertCode("CALLBACK_BINDING_CONFLICT",()->callbackInbox.accept(f.actor().tenantId(),wrongMerchant));
        assertCode("NOT_FOUND",()->callbackInbox.accept(f.actor().tenantId()+100000,input));
        assertEquals(100,count("SELECT amount FROM tc_payment_callback WHERE tenant_id=? AND callback_no=?",f.actor().tenantId(),number));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_callback WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void callbackTaskFailureRollsBackReceptionAndRejectsOuterTransaction() {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"ingress-fail").orderNo(),"TEST_A","return");
        var input=verifiedCallback(f,payment,"event","txn",100);
        com.lrj.trade.jobs.JobLeaseService jobTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobLeases);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("测试接收任务写入失败")).doCallRealMethod()
            .when(jobTarget).enqueue(eq(f.actor().tenantId()),eq(JobType.PAYMENT_CALLBACK),anyString());
        assertThrows(org.springframework.dao.TransientDataAccessException.class,()->callbackInbox.accept(f.actor().tenantId(),input));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_callback WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_CALLBACK'",f.actor().tenantId()));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(
            status->callbackInbox.accept(f.actor().tenantId(),input)));
        assertNotNull(callbackInbox.accept(f.actor().tenantId(),input));
    }

    @Test void staleCallbackWorkerRollsBackBusinessButKeepsDurableReception() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"stale-callback");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        String number=callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,payment,"event","txn",100));
        var old=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_CALLBACK,"old",1,30000).getFirst();
        expireLease(old);
        var current=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_CALLBACK,"current",1,30000).getFirst();
        assertEquals(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.LEASE_LOST,callbackWorker.processLease(old));
        assertEquals("PENDING",callbackState(f.actor().tenantId(),number));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE,callbackWorker.processLease(current));
        assertEquals("PROCESSED",callbackState(f.actor().tenantId(),number));
    }

    @Test void callbackSettlementFailureRetriesOriginalRecordWithoutLosingMoneyEvidence() {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"retry-callback").orderNo(),"TEST_A","return");
        String number=callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,payment,"event","txn",100));
        var lease=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_CALLBACK,"worker",1,30000).getFirst();
        com.lrj.trade.jobs.JobLeaseService jobTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobLeases);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("测试处理后提交前失败")).doCallRealMethod()
            .when(jobTarget).complete(eq(lease));
        assertEquals(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.RETRY,callbackWorker.processLease(lease));
        assertEquals("PENDING",callbackState(f.actor().tenantId(),number));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
        assertTrue(callbackWorker.runBatch(f.actor().tenantId(),"too-early",1).isEmpty());
        makeCallbacksDue(f.actor().tenantId());
        assertEquals(List.of(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE),callbackWorker.runBatch(f.actor().tenantId(),"retry",1));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void temporarilyMissingCallbackKeyRetriesAndRecoversWithoutChangingEvidence() {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"key-retry").orderNo(),"TEST_A","return");
        String number=callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,payment,"event","txn",100));
        doThrow(new TradeException("CALLBACK_ENCRYPTION_UNAVAILABLE","测试历史密钥暂不可用")).doCallRealMethod()
            .when(callbackProtector).decrypt(anyString(),any(com.lrj.trade.payment.model.EncryptedCallback.class));
        assertEquals(List.of(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.RETRY),callbackWorker.runBatch(f.actor().tenantId(),"first",1));
        assertEquals("PENDING",callbackState(f.actor().tenantId(),number));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        makeCallbacksDue(f.actor().tenantId());
        assertEquals(List.of(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE),callbackWorker.runBatch(f.actor().tenantId(),"second",1));
        assertEquals("test-v1",jdbc.queryForObject("SELECT key_version FROM tc_payment_callback WHERE tenant_id=?",String.class,f.actor().tenantId()));
    }

    @Test void tamperedCallbackFactsOrCipherAreRetainedForReviewWithoutSettlement() {
        Fixture f=paymentFixture(4); var payment=createPayment(f.actor(),"pay",factOrder(f,"tamper").orderNo(),"TEST_A","return");
        var other=createPayment(f.actor(),"other",factOrder(f,"other").orderNo(),"TEST_A","return");
        var input=verifiedCallback(f,payment,"facts","txn",100);
        String number=callbackInbox.accept(f.actor().tenantId(),input);
        String forgedHash=Checks.hash("payment-observation-v1",other.activeAttemptNo(),"RECEIVED","txn",100L,"CNY",input.providerPaidAt());
        jdbc.update("UPDATE tc_payment_callback SET request_no=?,fact_hash=? WHERE tenant_id=? AND callback_no=?",
            other.activeAttemptNo(),forgedHash,f.actor().tenantId(),number);
        assertEquals(List.of(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.REVIEW),callbackWorker.runBatch(f.actor().tenantId(),"review",1));
        assertEquals("REVIEW",callbackState(f.actor().tenantId(),number));
        String cipherNumber=callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,payment,"cipher","txn2",100));
        byte[] corrupt=jdbc.queryForObject("SELECT payload_cipher FROM tc_payment_callback WHERE tenant_id=? AND callback_no=?",byte[].class,f.actor().tenantId(),cipherNumber);
        corrupt[corrupt.length-1]^=1;
        jdbc.update("UPDATE tc_payment_callback SET payload_cipher=? WHERE tenant_id=? AND callback_no=?",corrupt,f.actor().tenantId(),cipherNumber);
        assertEquals(List.of(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.REVIEW),callbackWorker.runBatch(f.actor().tenantId(),"review",1));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(2,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
    }

    @Test void legitimateLeaseCannotBeUsedToCommitAnotherCallbackBusinessKey() {
        Fixture f=paymentFixture(4);
        var one=createPayment(f.actor(),"one",factOrder(f,"one").orderNo(),"TEST_A","return");
        var two=createPayment(f.actor(),"two",factOrder(f,"two").orderNo(),"TEST_A","return");
        String cbOne=callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,one,"one","txn1",100));
        String cbTwo=callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,two,"two","txn2",100));
        var lease=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_CALLBACK,"worker",1,30000).getFirst();
        String other=lease.businessKey().equals(cbOne)?cbTwo:cbOne;
        var forged=new com.lrj.trade.jobs.model.JobLease(lease.jobId(),lease.tenantId(),lease.jobType(),other,lease.owner(),lease.fencingToken(),lease.attempts(),lease.leaseUntil());
        assertEquals(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.LEASE_LOST,callbackWorker.processLease(forged));
        assertEquals("PENDING",callbackState(f.actor().tenantId(),other));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertCode("JOB_LEASE_LOST",()->jobLeases.renew(forged,30000));
        assertEquals(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE,callbackWorker.processLease(lease));
        assertEquals(List.of(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE),callbackWorker.runBatch(f.actor().tenantId(),"second",1));
    }

    @Test void reliableClosedCallbackReleasesOnlyAfterProcessingAndLateFundsRemainExceptional() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"closed-callback");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        orders.cancel(f.actor(),"cancel",order.orderNo(),"关闭");
        var closed=new com.lrj.trade.payment.model.VerifiedPaymentCallback("TEST_A","sandbox-"+f.actor().tenantId()+"-TEST_A",
            "closed",payment.activeAttemptNo(),"CLOSED",null,null,null,null,"test-cert-v1",new byte[]{1,2,3});
        callbackInbox.accept(f.actor().tenantId(),closed);
        assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        assertEquals(List.of(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE),callbackWorker.runBatch(f.actor().tenantId(),"close",1));
        assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status());
        callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,payment,"late","late-txn",100));
        assertEquals(List.of(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE),callbackWorker.runBatch(f.actor().tenantId(),"late",1));
        assertEquals("AFTER_TERMINAL",jdbc.queryForObject("SELECT disposition FROM tc_payment_receipt WHERE tenant_id=?",String.class,f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void duplicateCallbackWorkersCannotTurnProcessedEvidenceIntoReview() throws Exception {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"duplicate-worker").orderNo(),"TEST_A","return");
        String number=callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,payment,"event","txn",99));
        var lease=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_CALLBACK,"worker",1,30000).getFirst();
        var outcomes=parallel(10,()->callbackWorker.processLease(lease));
        assertEquals(1,outcomes.stream().filter(outcome->outcome==com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE).count());
        assertTrue(outcomes.stream().allMatch(outcome->outcome==com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE
            || outcome==com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.LEASE_LOST));
        assertEquals("PROCESSED",callbackState(f.actor().tenantId(),number));
        assertEquals("AMOUNT_MISMATCH",jdbc.queryForObject("SELECT disposition FROM tc_payment_receipt WHERE tenant_id=?",String.class,f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void callbackWorkerRecoversFromTransactionStartFailureWithoutLosingReception() {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"tx-begin").orderNo(),"TEST_A","return");
        String number=callbackInbox.accept(f.actor().tenantId(),verifiedCallback(f,payment,"event","txn",100));
        var lease=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_CALLBACK,"worker",1,30000).getFirst();
        var faulty=new com.lrj.trade.jobs.PaymentCallbackWorker(jobLeases,callbackInbox,callbackFaultManager(false));
        assertEquals(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.RETRY,faulty.processLease(lease));
        assertEquals("PENDING",callbackState(f.actor().tenantId(),number));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        makeCallbacksDue(f.actor().tenantId());
        assertEquals(List.of(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.DONE),callbackWorker.runBatch(f.actor().tenantId(),"recovered",1));
    }

    @Test void callbackWorkerCommitAcknowledgementLossCannotRestartCommittedTask() {
        Fixture f=paymentFixture(3); var payment=createPayment(f.actor(),"pay",factOrder(f,"tx-commit").orderNo(),"TEST_A","return");
        var input=verifiedCallback(f,payment,"event","txn",100);
        String number=callbackInbox.accept(f.actor().tenantId(),input);
        var lease=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_CALLBACK,"worker",1,30000).getFirst();
        var faulty=new com.lrj.trade.jobs.PaymentCallbackWorker(jobLeases,callbackInbox,callbackFaultManager(true));
        // 底层提交成功后模拟应答丢失；重试调度发现租约已终态，不能重启任务。
        assertEquals(com.lrj.trade.jobs.PaymentCallbackWorker.Outcome.LEASE_LOST,faulty.processLease(lease));
        assertEquals("PROCESSED",callbackState(f.actor().tenantId(),number));
        assertEquals(number,callbackInbox.accept(f.actor().tenantId(),input));
        assertTrue(callbackWorker.runBatch(f.actor().tenantId(),"replay",1).isEmpty());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
    }

    private PlatformTransactionManager callbackFaultManager(boolean afterCommit) {
        var armed=new java.util.concurrent.atomic.AtomicBoolean(true);
        return new PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                if(!afterCommit && armed.compareAndSet(true,false))
                    throw new org.springframework.transaction.CannotCreateTransactionException("测试事务开启不可用");
                return transactions.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                transactions.commit(status);
                if(afterCommit && armed.compareAndSet(true,false))
                    throw new org.springframework.transaction.TransactionSystemException("测试提交后应答丢失");
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { transactions.rollback(status); }
        };
    }

    private com.lrj.trade.payment.model.VerifiedPaymentCallback verifiedCallback(Fixture f,com.lrj.trade.payment.model.PaymentView payment,
            String event,String transaction,long amount) {
        return new com.lrj.trade.payment.model.VerifiedPaymentCallback("TEST_A","sandbox-"+f.actor().tenantId()+"-TEST_A",event,
            payment.activeAttemptNo(),"RECEIVED",transaction,amount,"CNY",Instant.parse("2026-09-07T07:59:59.123Z"),"test-cert-v1",
            ("test-event="+event+",transaction="+transaction).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String callbackState(long tenant,String number) {
        return jdbc.queryForObject("SELECT status FROM tc_payment_callback WHERE tenant_id=? AND callback_no=?",String.class,tenant,number);
    }

    private void makeCallbacksDue(long tenant) {
        jdbc.update("UPDATE tc_job_lease SET next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND job_type='PAYMENT_CALLBACK' AND status='READY'",tenant);
    }

    @Test void paymentRecoveryTaskIsAtomicAndPermanentAcrossRequestReplays() {
        Fixture f=paymentFixture(4); var order=factOrder(f,"job-rollback");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            createPayment(f.actor(),"pay-rollback",order.orderNo(),"TEST_A","return");
            throw new IllegalStateException("业务事务失败");
        }));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER'",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_attempt WHERE tenant_id=?",f.actor().tenantId()));
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        assertEquals(payment,createPayment(f.actor(),"pay-again",order.orderNo(),"TEST_A","return"));
        var lease=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"worker-1",10,30000).getFirst();
        assertEquals(payment.activeAttemptNo(),lease.businessKey());
        new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.complete(lease));
        enqueueJob(f.actor().tenantId(),payment.activeAttemptNo());
        assertTrue(jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"worker-2",10,30000).isEmpty());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER'",f.actor().tenantId()));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
            ()->jobLeases.enqueue(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"without-tx"));
    }

    @Test void concurrentWorkersClaimDisjointBoundedTenantJobs() throws Exception {
        long tenant=actor().tenantId(),other=actor().tenantId();
        for(int i=0;i<24;i++) enqueueJob(tenant,"job-"+i);
        enqueueJob(other,"other");
        AtomicLong workers=new AtomicLong();
        var batches=parallel(12,()->jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"worker-"+workers.incrementAndGet(),3,30000));
        var leases=batches.stream().flatMap(List::stream).toList();
        assertEquals(24,leases.size()); assertEquals(24,leases.stream().map(com.lrj.trade.jobs.model.JobLease::jobId).distinct().count());
        assertTrue(batches.stream().allMatch(batch->batch.size()<=3));
        assertTrue(leases.stream().allMatch(lease->lease.tenantId()==tenant && lease.fencingToken()==1 && lease.attempts()==1));
        assertEquals(1,jobLeases.claim(other,JobType.PAYMENT_RECOVER,"other-worker",1,30000).size());
        assertTrue(jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"empty",100,30000).isEmpty());
    }

    @Test void expiredLeaseRequiresNewTokenEvenForSameOwnerAndRejectsForeignClaims() {
        long tenant=actor().tenantId(); enqueueJob(tenant,"recover");
        var first=jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"same-owner",1,30000).getFirst();
        expireLease(first);
        assertCode("JOB_LEASE_LOST",()->jobLeases.renew(first,30000));
        assertCode("JOB_LEASE_LOST",()->new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.complete(first)));
        var next=jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"same-owner",1,30000).getFirst();
        assertEquals(first.jobId(),next.jobId()); assertEquals(2,next.fencingToken()); assertEquals(2,next.attempts());
        assertCode("JOB_LEASE_LOST",()->new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.complete(first)));
        var wrongOwner=new com.lrj.trade.jobs.model.JobLease(next.jobId(),tenant,next.jobType(),next.businessKey(),"intruder",next.fencingToken(),next.attempts(),next.leaseUntil());
        assertCode("JOB_LEASE_LOST",()->jobLeases.renew(wrongOwner,30000));
        var wrongTenant=new com.lrj.trade.jobs.model.JobLease(next.jobId(),tenant+100000,next.jobType(),next.businessKey(),next.owner(),next.fencingToken(),next.attempts(),next.leaseUntil());
        assertCode("JOB_LEASE_LOST",()->jobLeases.renew(wrongTenant,30000));
        var renewed=jobLeases.renew(next,60000);
        assertEquals(next.fencingToken(),renewed.fencingToken());
        assertTrue(renewed.leaseUntil().isAfter(next.leaseUntil()));
        assertFalse(jobLeases.renew(renewed,1000).leaseUntil().isBefore(renewed.leaseUntil()));
    }

    @Test void staleCompletionRollsBackPaymentMoneyStockFactsAndCannotBeSwallowed() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"fenced");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var old=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"old",1,30000).getFirst();
        expireLease(old);
        var current=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"new",1,30000).getFirst();
        assertCode("JOB_LEASE_LOST",()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            receive(f,payment,"fenced-txn",100,"CNY");
            jobLeases.complete(old);
        }));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        assertThrows(org.springframework.transaction.UnexpectedRollbackException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            receive(f,payment,"fenced-txn",100,"CNY");
            try { jobLeases.complete(old); } catch(TradeException ignored) { /* 捕获也不能解除事务回滚标记。 */ }
        }));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            receive(f,payment,"fenced-txn",100,"CNY");
            jobLeases.complete(current);
        });
        assertEquals("PAID",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
        assertCode("JOB_LEASE_LOST",()->new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.complete(current)));
    }

    @Test void retryDelayAndReviewPreserveTaskWithoutReleasingBusinessResources() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"retry");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var first=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"worker",1,30000).getFirst();
        new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.retry(first,60000,"CHANNEL_TIMEOUT"));
        assertTrue(jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"early",1,30000).isEmpty());
        assertEquals("CHANNEL_TIMEOUT",jdbc.queryForObject("SELECT last_error FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER'",String.class,f.actor().tenantId()));
        jdbc.update("UPDATE tc_job_lease SET next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND id=?",first.tenantId(),first.jobId());
        var second=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"worker",1,30000).getFirst();
        new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.review(second,"CHANNEL_UNSUPPORTED"));
        enqueueJob(f.actor().tenantId(),payment.activeAttemptNo());
        assertTrue(jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"later",1,30000).isEmpty());
        assertEquals(2,count("SELECT attempts FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER'",f.actor().tenantId()));
        assertEquals("REVIEW",jdbc.queryForObject("SELECT status FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER'",String.class,f.actor().tenantId()));
        assertEquals(1,inventory.balances(f.actor().tenantId(),0,100).getFirst().reserved());
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
    }

    @Test void claimingSkipsLockedJobInsteadOfBlockingOtherWork() throws Exception {
        long tenant=actor().tenantId(); enqueueJob(tenant,"locked"); enqueueJob(tenant,"free");
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        var holder=executor.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            jdbc.queryForObject("SELECT id FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER' AND business_key='locked' FOR UPDATE",Long.class,tenant);
            locked.countDown();
            try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("等待释放超时"); }
            catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        }));
        try {
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var claimed=jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"worker",10,30000);
            assertEquals(1,claimed.size()); assertEquals("free",claimed.getFirst().businessKey());
        } finally { release.countDown(); holder.get(15,TimeUnit.SECONDS); executor.shutdownNow(); }
        assertEquals("locked",jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"worker",10,30000).getFirst().businessKey());
    }

    @Test void jobLeaseBoundariesDatabaseClockAndStateConstraintsFailClosed() {
        long tenant=actor().tenantId(); enqueueJob(tenant,"clock");
        when(clock.instant()).thenReturn(Instant.parse("1999-01-01T00:00:00Z"));
        assertCode("INVALID_ARGUMENT",()->jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"worker",101,30000));
        assertCode("INVALID_ARGUMENT",()->jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"worker",1,999));
        assertCode("INVALID_ARGUMENT",()->jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"worker",1,300001));
        assertCode("INVALID_ARGUMENT",()->jobLeases.claim(tenant,null,"worker",1,30000));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(
            status->jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"worker",1,30000)));
        var lease=jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"worker",1,30000).getFirst();
        assertTrue(lease.leaseUntil().isAfter(Instant.parse("2000-01-01T00:00:00Z")));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->jobLeases.complete(lease));
        assertCode("INVALID_ARGUMENT",()->new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.retry(lease,0,"TIMEOUT")));
        assertCode("INVALID_ARGUMENT",()->new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.retry(lease,100,"secret=https://key")));
        var violation=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE tc_job_lease SET owner=NULL WHERE tenant_id=? AND id=?",tenant,lease.jobId()));
        assertEquals(3819,((java.sql.SQLException)violation.getMostSpecificCause()).getErrorCode());
        new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.complete(lease));
    }

    @Autowired com.lrj.trade.payment.PaymentRecoveryBackfillService recoveryBackfill;

    @Test void upgradeCatchupRepairsLegacyAttemptsWithoutRestartingCompletedJobs() {
        Fixture f=paymentFixture(5);
        var first=createPayment(f.actor(),"first",factOrder(f,"first").orderNo(),"TEST_A","return");
        var second=createPayment(f.actor(),"second",factOrder(f,"second").orderNo(),"TEST_A","return");
        var lease=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"worker",1,30000).getFirst();
        new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.complete(lease));
        // 隔离测试模拟V007旧实例在回填后产生的无任务尝试，绝不操作共享库。
        String missing=lease.businessKey().equals(first.activeAttemptNo())?second.activeAttemptNo():first.activeAttemptNo();
        jdbc.update("DELETE FROM tc_job_lease WHERE tenant_id=? AND business_key=?",f.actor().tenantId(),missing);
        long cursor=0;
        for(int i=0;i<2;i++) {
            long next=recoveryBackfill.enqueuePage(f.actor().tenantId(),cursor,1);
            assertTrue(next>cursor); cursor=next;
        }
        assertEquals(cursor,recoveryBackfill.enqueuePage(f.actor().tenantId(),cursor,1));
        recoveryBackfill.enqueuePage(f.actor().tenantId(),0,100);
        assertEquals(2,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER'",f.actor().tenantId()));
        var recovered=jobLeases.claim(f.actor().tenantId(),JobType.PAYMENT_RECOVER,"recover",10,30000);
        assertEquals(1,recovered.size()); assertEquals(missing,recovered.getFirst().businessKey());
        assertEquals(0,recoveryBackfill.enqueuePage(f.actor().tenantId()+100000,0,100));
        assertCode("INVALID_ARGUMENT",()->recoveryBackfill.enqueuePage(f.actor().tenantId(),-1,100));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(
            status->recoveryBackfill.enqueuePage(f.actor().tenantId(),0,100)));
    }

    @Test void dueJobScanUsesOrderedIndexWithoutFilesort() {
        long tenant=actor().tenantId();
        for(int i=0;i<20;i++) enqueueJob(tenant,"index-"+i);
        var plan=jdbc.queryForList("EXPLAIN SELECT id FROM tc_job_lease WHERE tenant_id=? AND job_type='PAYMENT_RECOVER' AND runnable=1 AND next_run_at<=UTC_TIMESTAMP(3) ORDER BY next_run_at,id LIMIT 10 FOR UPDATE SKIP LOCKED",tenant);
        assertEquals("idx_job_due",plan.getFirst().get("key"));
        assertFalse(String.valueOf(plan.getFirst().get("Extra")).toLowerCase(Locale.ROOT).contains("filesort"));
    }

    @Test void waitingForJobRowLockCannotExtendAnExpiredLeaseOrCompleteIt() throws Exception {
        long tenant=actor().tenantId(); enqueueJob(tenant,"lock-expiry");
        var lease=jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"worker",1,30000).getFirst();
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(2);
        var executor=Executors.newFixedThreadPool(3);
        var holder=executor.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            jdbc.queryForObject("SELECT id FROM tc_job_lease WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,tenant,lease.jobId());
            // 在持锁事务中设置近期到期点，等待中的调用必须解锁后再判断主库时间。
            jdbc.update("UPDATE tc_job_lease SET lease_until=TIMESTAMPADD(SECOND,1,UTC_TIMESTAMP(3)),next_run_at=lease_until WHERE tenant_id=? AND id=?",tenant,lease.jobId());
            locked.countDown();
            try { if(!release.await(8,TimeUnit.SECONDS)) throw new IllegalStateException("等待释放超时"); }
            catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        }));
        Future<?> renewal=null,completion=null;
        try {
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            renewal=executor.submit(()->{ started.countDown(); assertCode("JOB_LEASE_LOST",()->jobLeases.renew(lease,30000)); });
            completion=executor.submit(()->{ started.countDown(); assertCode("JOB_LEASE_LOST",()->new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.complete(lease))); });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            // 保持真实数据库行锁跨越到期点；不是先失效再调用的顺序测试。
            final Future<?> waiting=renewal;
            assertThrows(TimeoutException.class,()->waiting.get(1500,TimeUnit.MILLISECONDS));
            assertFalse(completion.isDone());
        } finally {
            release.countDown(); holder.get(10,TimeUnit.SECONDS);
            try {
                if(renewal!=null) renewal.get(10,TimeUnit.SECONDS);
                if(completion!=null) completion.get(10,TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
        }
        assertEquals(2,jobLeases.claim(tenant,JobType.PAYMENT_RECOVER,"replacement",1,30000).getFirst().fencingToken());
    }

    private void enqueueJob(long tenant,String key) {
        new TransactionTemplate(transactions).executeWithoutResult(status->jobLeases.enqueue(tenant,JobType.PAYMENT_RECOVER,key));
    }

    private void expireLease(com.lrj.trade.jobs.model.JobLease lease) {
        // 只操作Testcontainers隔离库，确定性模拟主库时间到期，不对公共实例注入故障。
        jdbc.update("UPDATE tc_job_lease SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)),next_run_at=lease_until WHERE tenant_id=? AND id=?",
            lease.tenantId(),lease.jobId());
    }

    @Test void paymentCreationIsAtomicAndKeepsOneIntentAndAttempt() throws Exception {
        Fixture f=paymentFixture(5); var order=factOrder(f,"payment");
        var results=parallel(40,()->createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return"));
        assertEquals(1,results.stream().distinct().count());
        var payment=results.getFirst();
        assertEquals("PENDING",payment.status()); assertEquals("PREPARED",payment.attemptStatus());
        assertEquals(payment,createPayment(f.actor(),"another-key",order.orderNo(),"TEST_A","return"));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_intent WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_attempt WHERE tenant_id=?",f.actor().tenantId()));
        assertCode("IDEMPOTENCY_CONFLICT",()->createPayment(f.actor(),"pay",order.orderNo(),"TEST_B","return"));
        assertCode("PAYMENT_UNRESOLVED",()->createPayment(f.actor(),"switch",order.orderNo(),"TEST_B","return"));
        Fixture absent=orderFixture(3); var noChannel=factOrder(absent,"missing");
        assertCode("CHANNEL_UNAVAILABLE",()->createPayment(absent.actor(),"pay",noChannel.orderNo(),"TEST_A","return"));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_payment_intent WHERE tenant_id=?",absent.actor().tenantId()));
        assertNull(jdbc.queryForObject("SELECT payment_started_at FROM tc_order WHERE tenant_id=?",java.sql.Timestamp.class,absent.actor().tenantId()));
        assertEquals("CANCELLED",orders.cancel(absent.actor(),"cancel",noChannel.orderNo(),"没有许可").status());
    }

    @Test void paymentDispatchUsesSameRequestAndReliableCloseReleasesOnlyAfterProof() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"close");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->new TransactionTemplate(transactions).execute(status->
            paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo())));
        var first=paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo());
        assertEquals("CREATE",first.action()); assertEquals(100,first.amount()); assertEquals("CNY",first.currency());
        var retry=paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo());
        assertEquals("QUERY",retry.action()); assertEquals(first.requestNo(),retry.requestNo());
        assertEquals("UNKNOWN",payments.read(f.actor(),payment.paymentNo()).attemptStatus());
        assertEquals("CLOSING",orders.cancel(f.actor(),"cancel",order.orderNo(),"关闭").status());
        assertEquals(1,balance(f).reserved());
        assertEquals("CLOSE",paymentDispatch.prepare(f.actor().tenantId(),first.requestNo()).action());
        assertCode("INVALID_ARGUMENT",()->paymentObservations.closed(f.actor().tenantId(),first.requestNo(),""));
        assertEquals(1,balance(f).reserved());
        paymentObservations.closed(f.actor().tenantId(),first.requestNo(),"sandbox:durable-nonpayable");
        paymentObservations.closed(f.actor().tenantId(),first.requestNo(),"sandbox:repeat-proof");
        assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status());
        assertEquals("CLOSED",payments.read(f.actor(),payment.paymentNo()).status());
        assertEquals(3,balance(f).available()); assertEquals(0,balance(f).reserved());
        assertEquals("DONE",paymentDispatch.prepare(f.actor().tenantId(),first.requestNo()).action());
        var late=receive(f,payment,"late",100,"CNY");
        assertEquals("AFTER_TERMINAL",late.disposition());
        assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status());
        assertFalse(members.me(f.actor()).everPaid()); assertEquals(0,balance(f).sold());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-paid.v1'",f.actor().tenantId()));
    }

    @Test void normalPaymentReplayAndExcessRecordEveryFundButFulfillOnce() throws Exception {
        Fixture f=paymentFixture(5); var order=factOrder(f,"paid");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        long member=Long.parseLong(members.me(f.actor()).memberId());
        members.changeStatus(f.actor(),"freeze",member,0,"FROZEN","合法收款不能丢失");
        var results=parallel(100,()->receive(f,payment,"provider-one",100,"CNY"));
        assertEquals(1,results.stream().distinct().count()); assertEquals("NORMAL",results.getFirst().disposition());
        var paid=orders.read(f.actor(),order.orderNo());
        assertEquals("PAID",paid.status()); assertEquals("PAID",paid.financialStatus()); assertNotNull(paid.paidAt()); assertNull(paid.completedAt());
        assertEquals(paid.paidAt(),tradeFacts.read(f.actor().tenantId(),member).firstConfirmedAt());
        assertEquals(1,balance(f).sold()); assertEquals(0,balance(f).reserved());
        assertEquals("EXCESS",receive(f,payment,"provider-two",100,"CNY").disposition());
        assertCode("PAYMENT_FACT_CONFLICT",()->receive(f,payment,"provider-one",101,"CNY"));
        assertEquals(2,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(2,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,tradeFacts.read(f.actor().tenantId(),member).paidOrderCount());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-paid.v1'",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='order.paid.internal.v0'",f.actor().tenantId()));
        assertCode("STATE_CONFLICT",()->paymentObservations.closed(f.actor().tenantId(),payment.activeAttemptNo(),"invalid-close-after-paid"));
        assertConservation(f);
    }

    @Test void mismatchedFundsAreDurableAndDoNotConfirmOrder() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"mismatch");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        assertEquals("AMOUNT_MISMATCH",receive(f,payment,"wrong-amount",99,"CNY").disposition());
        assertEquals("AMOUNT_MISMATCH",receive(f,payment,"wrong-currency",100,"USD").disposition());
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
        assertEquals("REVIEW",payments.read(f.actor(),payment.paymentNo()).status());
        assertFalse(members.me(f.actor()).everPaid()); assertEquals(1,balance(f).reserved());
        assertEquals(2,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals("REVIEW",paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo()).action());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-paid.v1'",f.actor().tenantId()));
    }

    @Test void paymentSettlementRollsBackAllDomainsOnCallerFailure() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"rollback-payment");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            receive(f,payment,"rollback",100,"CNY");
            throw new IllegalStateException("模拟主事务提交前失败");
        }));
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(1,balance(f).reserved()); assertEquals(0,balance(f).sold()); assertFalse(members.me(f.actor()).everPaid());
        for(String table:List.of("tc_payment_receipt","tc_money_ledger","tc_member_paid_order"))
            assertEquals(0,count("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type IN ('order.paid.internal.v0','payment.received.internal.v0','member.paid-order.internal.v0','trade.order-paid.v1')",f.actor().tenantId()));
        assertEquals("NORMAL",receive(f,payment,"rollback",100,"CNY").disposition());
    }

    @Test void closedAttemptCanSwitchChannelAndOldProofCannotCloseNewAttempt() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"switch");
        var first=createPayment(f.actor(),"pay-a",order.orderNo(),"TEST_A","return");
        paymentDispatch.prepare(f.actor().tenantId(),first.activeAttemptNo());
        assertCode("PAYMENT_UNRESOLVED",()->createPayment(f.actor(),"pay-b",order.orderNo(),"TEST_B","return"));
        paymentObservations.closed(f.actor().tenantId(),first.activeAttemptNo(),"sandbox:a-terminal");
        assertEquals("READY",payments.read(f.actor(),first.paymentNo()).status());
        var second=createPayment(f.actor(),"pay-b",order.orderNo(),"TEST_B","return");
        assertEquals(first.paymentNo(),second.paymentNo()); assertNotEquals(first.activeAttemptNo(),second.activeAttemptNo());
        paymentObservations.closed(f.actor().tenantId(),first.activeAttemptNo(),"sandbox:old-proof");
        assertEquals(second.activeAttemptNo(),payments.read(f.actor(),second.paymentNo()).activeAttemptNo());
        assertEquals("CREATE",paymentDispatch.prepare(f.actor().tenantId(),second.activeAttemptNo()).action());
        assertEquals("AFTER_TERMINAL",receive(f,first,"contradictory-old",100,"CNY").disposition());
        assertFalse(members.me(f.actor()).everPaid());
        assertEquals("NORMAL",receive(f,second,"new-channel",100,"CNY").disposition());
        assertEquals(1,balance(f).sold());
    }

    @Test void cancellingAfterAllAttemptsClosedCanStillReleaseAndExpiredPaymentCannotCreate() {
        Fixture f=paymentFixture(3); var order=factOrder(f,"ready");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        paymentObservations.closed(f.actor().tenantId(),payment.activeAttemptNo(),"sandbox:terminal");
        assertEquals("CLOSING",orders.cancel(f.actor(),"cancel",order.orderNo(),"稍后取消").status());
        assertEquals("DONE",paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo()).action());
        paymentObservations.closed(f.actor().tenantId(),payment.activeAttemptNo(),"sandbox:recheck");
        assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status()); assertEquals(3,balance(f).available());
        var other=factOrder(f,"expired"); var pending=createPayment(f.actor(),"pay-expired",other.orderNo(),"TEST_A","return");
        when(clock.instant()).thenReturn(other.expiresAt());
        assertEquals("CLOSE",paymentDispatch.prepare(f.actor().tenantId(),pending.activeAttemptNo()).action());
        assertEquals("CLOSING",orders.read(f.actor(),other.orderNo()).status()); assertEquals(1,balance(f).reserved());
    }

    @Test void closeAndRealReceiptCompeteWithoutLosingFundsOrDoubleStockEffects() throws Exception {
        Fixture f=paymentFixture(3); var order=factOrder(f,"race");
        var payment=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        paymentDispatch.prepare(f.actor().tenantId(),payment.activeAttemptNo());
        orders.cancel(f.actor(),"cancel",order.orderNo(),"竞争关单");
        var barrier=new CountDownLatch(2); var sequence=new AtomicLong();
        parallel(2,()->{
            long n=sequence.incrementAndGet(); barrier.countDown(); assertTrue(barrier.await(10,TimeUnit.SECONDS));
            if(n==1) receive(f,payment,"race-transaction",100,"CNY");
            else try { paymentObservations.closed(f.actor().tenantId(),payment.activeAttemptNo(),"sandbox:terminal-race"); }
                catch(TradeException conflict) { assertEquals("STATE_CONFLICT",conflict.code()); }
            return true;
        });
        var end=orders.read(f.actor(),order.orderNo());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_payment_receipt WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,balance(f).reserved());
        if(end.status().equals("PAID")) { assertEquals(1,balance(f).sold()); assertTrue(members.me(f.actor()).everPaid()); }
        else { assertEquals("CANCELLED",end.status()); assertEquals(0,balance(f).sold()); assertFalse(members.me(f.actor()).everPaid()); }
        assertConservation(f);
    }

    @Test void twoPaidOrdersChooseOneFirstDespiteLateEarlierProviderTimestamp() throws Exception {
        Fixture f=paymentFixture(5); var one=factOrder(f,"first"); var two=factOrder(f,"second"); var late=factOrder(f,"late");
        var p1=createPayment(f.actor(),"p1",one.orderNo(),"TEST_A","return");
        var p2=createPayment(f.actor(),"p2",two.orderNo(),"TEST_A","return");
        var p3=createPayment(f.actor(),"p3",late.orderNo(),"TEST_A","return");
        var sequence=new AtomicLong();
        parallel(2,()->sequence.incrementAndGet()==1?receive(f,p1,"one",100,"CNY"):receive(f,p2,"two",100,"CNY"));
        long member=Long.parseLong(members.me(f.actor()).memberId());
        var before=tradeFacts.read(f.actor().tenantId(),member);
        paymentObservations.received(f.actor().tenantId(),p3.activeAttemptNo(),"older",100,"CNY",Instant.parse("2026-09-01T00:00:00Z"),"sandbox:older");
        assertEquals(before.firstPaidOrderId(),tradeFacts.read(f.actor().tenantId(),member).firstPaidOrderId());
        assertEquals(3,tradeFacts.read(f.actor().tenantId(),member).paidOrderCount());
        assertEquals(1,count("SELECT SUM(is_first) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(3,balance(f).sold()); assertConservation(f);
    }

    @Test void sameProviderTransactionCannotFundAnotherOrder() {
        Fixture f=paymentFixture(4); var one=factOrder(f,"one"); var two=factOrder(f,"two");
        var p1=createPayment(f.actor(),"p1",one.orderNo(),"TEST_A","return");
        var p2=createPayment(f.actor(),"p2",two.orderNo(),"TEST_A","return");
        receive(f,p1,"same-provider-transaction",100,"CNY");
        assertCode("PAYMENT_FACT_CONFLICT",()->receive(f,p2,"same-provider-transaction",100,"CNY"));
        assertEquals("PENDING_PAYMENT",orders.read(f.actor(),two.orderNo()).status());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,balance(f).sold()); assertEquals(1,balance(f).reserved());
        assertCode("NOT_FOUND",()->paymentObservations.received(actor().tenantId(),p1.activeAttemptNo(),"foreign",100,"CNY",
            Instant.parse("2026-09-07T07:59:59Z"),"sandbox:foreign"));
    }

    @Test void switchingClosingAndCancellingRaceNeverReleasesAnOpenAttempt() throws Exception {
        Fixture f=paymentFixture(4); var order=factOrder(f,"three-way");
        var p=createPayment(f.actor(),"p1",order.orderNo(),"TEST_A","return");
        paymentDispatch.prepare(f.actor().tenantId(),p.activeAttemptNo());
        var barrier=new CountDownLatch(3); var sequence=new AtomicLong();
        parallel(3,()->{
            long n=sequence.incrementAndGet(); barrier.countDown(); assertTrue(barrier.await(10,TimeUnit.SECONDS));
            if(n==1) paymentObservations.closed(f.actor().tenantId(),p.activeAttemptNo(),"sandbox:old-terminal");
            else if(n==2) {
                try { createPayment(f.actor(),"p2",order.orderNo(),"TEST_B","return"); }
                catch(TradeException conflict) { assertTrue(Set.of("STATE_CONFLICT","PAYMENT_UNRESOLVED").contains(conflict.code())); }
            } else orders.cancel(f.actor(),"cancel",order.orderNo(),"并发取消");
            return true;
        });
        var current=payments.read(f.actor(),p.paymentNo());
        if(current.activeAttemptNo()!=null) {
            assertNotEquals(p.activeAttemptNo(),current.activeAttemptNo());
            assertEquals("CLOSING",orders.read(f.actor(),order.orderNo()).status()); assertEquals(1,balance(f).reserved());
            assertEquals("CLOSE",paymentDispatch.prepare(f.actor().tenantId(),current.activeAttemptNo()).action());
            paymentObservations.closed(f.actor().tenantId(),current.activeAttemptNo(),"sandbox:new-terminal");
        } else paymentDispatch.prepare(f.actor().tenantId(),p.activeAttemptNo());
        assertEquals("CANCELLED",orders.read(f.actor(),order.orderNo()).status());
        assertEquals(4,balance(f).available()); assertEquals(0,balance(f).reserved()); assertConservation(f);
    }

    @Test void paymentHttpRequiresOwnershipAndNeverAcceptsClientAmountOrSuccess() throws Exception {
        Fixture f=fixture(5); long tenant=f.actor().tenantId();
        jwt("payment-admin","trade.admin",tenant); jwt("payment-member","",tenant); jwt("payment-other","",tenant);
        Actor buyer=new Actor(tenant,"https://identity.test","payment-member"); members.register(buyer,"支付用户");
        members.register(new Actor(tenant,"https://identity.test","payment-other"),"其他用户");
        merchants.create(f.actor(),"merchant","logical-merchant","测试商户");
        String channel="{\"channelCode\":\"TEST_A\",\"channelMerchantNo\":\"http-"+tenant+"\",\"currency\":\"CNY\",\"credentialRef\":\"vault:test/payment\",\"returnUrlId\":\"return\",\"returnUrl\":\"https://shop.test/return\"}";
        assertEquals(403,request("POST","/api/v1/trade/admin/payment-channels","payment-member",channel,"config").statusCode());
        assertEquals(200,request("POST","/api/v1/trade/admin/payment-channels","payment-admin",channel,"config").statusCode());
        var listed=request("GET","/api/v1/trade/admin/payment-channels","payment-admin",null);
        assertEquals(200,listed.statusCode()); assertFalse(listed.body().contains("vault:")); assertFalse(listed.body().contains("credentialRef"));
        var quote=quotes.create(buyer,"quote",List.of(new QuoteSelection(Long.toString(f.sku()),1L)));
        var order=orders.create(buyer,"order",Long.parseLong(quote.quoteId()),f.warehouse(),shipping());
        String path="/api/v1/trade/orders/"+order.orderNo()+"/payments";
        assertEquals(400,request("POST",path,"payment-member","{\"channelCode\":\"TEST_A\",\"returnUrlId\":\"return\",\"amount\":1}","bad").statusCode());
        assertEquals(404,request("POST",path,"payment-other","{\"channelCode\":\"TEST_A\",\"returnUrlId\":\"return\"}","attack").statusCode());
        var response=request("POST",path,"payment-member","{\"channelCode\":\"TEST_A\",\"returnUrlId\":\"return\"}","pay");
        assertEquals(202,response.statusCode(),response.body()); assertFalse(response.body().contains("vault:"));
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        String no=json.readTree(response.body()).path("paymentNo").asText();
        assertEquals(200,request("GET","/api/v1/trade/payments/"+no,"payment-member",null).statusCode());
        assertEquals(404,request("GET","/api/v1/trade/payments/"+no,"payment-other",null).statusCode());
        assertEquals(403,request("POST","/callbacks/v1/payments/TEST_A","payment-member","{\"success\":true}").statusCode());
    }

    private Fixture paymentFixture(long stock) {
        Fixture f=orderFixture(stock);
        for(String code:List.of("TEST_A","TEST_B")) paymentChannels.create(f.actor(),"channel-"+code,code,
            "sandbox-"+f.actor().tenantId()+"-"+code,"CNY","vault:test/"+code,"return","https://shop.test/return");
        return f;
    }
    /** 仅模拟内部已验真事实输入，真实原始报文验签和网络交错须后续适配器测试补齐。 */
    private com.lrj.trade.payment.model.PaymentReceipt receive(Fixture f,com.lrj.trade.payment.model.PaymentView payment,
            String transaction,long amount,String currency) {
        return paymentObservations.received(f.actor().tenantId(),payment.activeAttemptNo(),transaction,amount,currency,
            Instant.parse("2026-09-07T07:59:59.123Z"),"sandbox:verified-observation");
    }

    @Test void paidOrderFactsSerializeFirstAndKeepOriginalOnLateConfirmation() throws Exception {
        Fixture f=orderFixture(10);
        var one=factOrder(f,"one"); var two=factOrder(f,"two"); var late=factOrder(f,"late");
        long member=Long.parseLong(members.me(f.actor()).memberId());
        var sequence=new AtomicLong();
        var results=parallel(100,()->confirmFact(f,sequence.incrementAndGet()%2==0?one:two));
        assertEquals(2,results.stream().map(com.lrj.trade.member.model.PaidOrderFact::orderId).distinct().count());
        assertEquals(1,results.stream().filter(com.lrj.trade.member.model.PaidOrderFact::firstConfirmedPaidOrder)
            .map(com.lrj.trade.member.model.PaidOrderFact::orderId).distinct().count());
        var before=tradeFacts.read(f.actor().tenantId(),member);
        assertTrue(before.everPaid()); assertEquals(2,before.paidOrderCount()); assertEquals(2,before.factVersion());
        // 即使本地时钟回拨，首单也依赖提交成功的事实裁决，不按时间排序重选。
        when(clock.instant()).thenReturn(Instant.parse("2026-09-06T08:00:00.000Z"));
        assertFalse(confirmFact(f,late).firstConfirmedPaidOrder());
        var after=tradeFacts.read(f.actor().tenantId(),member);
        assertEquals(before.firstPaidOrderId(),after.firstPaidOrderId());
        assertEquals(before.firstConfirmedAt(),after.firstConfirmedAt());
        assertEquals(3,after.paidOrderCount()); assertEquals(3,after.factVersion());
        assertEquals(3,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(3,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='member.paid-order.internal.v0'",f.actor().tenantId()));
    }

    @Test void paidOrderFactsRollbackWithCallerAndRequireTransaction() {
        Fixture f=orderFixture(3); var order=factOrder(f,"rollback");
        long member=Long.parseLong(members.me(f.actor()).memberId());
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
            ()->tradeFacts.confirmPaidOrder(f.actor().tenantId(),member,Long.parseLong(order.orderId()),100,"CNY"));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            tradeFacts.confirmPaidOrder(f.actor().tenantId(),member,Long.parseLong(order.orderId()),100,"CNY");
            throw new IllegalStateException("模拟收款账本失败，必须回滚首单");
        }));
        assertFalse(tradeFacts.read(f.actor().tenantId(),member).everPaid());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='member.paid-order.internal.v0'",f.actor().tenantId()));
        assertTrue(confirmFact(f,order).firstConfirmedPaidOrder());
    }

    @Test void paidOrderFactReplayUsesCurrentReadAfterOldSnapshot() throws Exception {
        Fixture f=orderFixture(3); var order=factOrder(f,"snapshot");
        long member=Long.parseLong(members.me(f.actor()).memberId());
        try(var worker=Executors.newSingleThreadExecutor()) {
            var result=new TransactionTemplate(transactions).execute(status->{
                assertFalse(tradeFacts.read(f.actor().tenantId(),member).everPaid());
                try {
                    var original=worker.submit(()->confirmFact(f,order)).get(10,TimeUnit.SECONDS);
                    var replay=tradeFacts.confirmPaidOrder(f.actor().tenantId(),member,Long.parseLong(order.orderId()),100,"CNY");
                    assertEquals(original,replay);
                    return replay;
                } catch(Exception failure) { throw new AssertionError(failure); }
            });
            assertTrue(result.firstConfirmedPaidOrder()); assertEquals(1,result.factVersion());
        }
        assertEquals(1,tradeFacts.read(f.actor().tenantId(),member).paidOrderCount());
    }

    @Test void paidOrderFactsRejectZeroConflictingMoneyAndForeignOwnership() {
        Fixture f=orderFixture(5); var order=factOrder(f,"validation");
        long member=Long.parseLong(members.me(f.actor()).memberId()),id=Long.parseLong(order.orderId());
        for(long amount:List.of(0L,-1L,9007199254740992L))
            assertCode("INVALID_ARGUMENT",()->new TransactionTemplate(transactions).execute(status->
                tradeFacts.confirmPaidOrder(f.actor().tenantId(),member,id,amount,"CNY")));
        assertCode("INVALID_ARGUMENT",()->new TransactionTemplate(transactions).execute(status->
            tradeFacts.confirmPaidOrder(f.actor().tenantId(),member,id,100,"cny")));
        Actor other=new Actor(f.actor().tenantId(),f.actor().issuer(),"foreign-fact-member");
        long otherMember=Long.parseLong(members.register(other,"其他会员"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->new TransactionTemplate(transactions).execute(status->
            tradeFacts.confirmPaidOrder(f.actor().tenantId(),otherMember,id,100,"CNY")));
        assertFalse(tradeFacts.read(f.actor().tenantId(),otherMember).everPaid());
        assertCode("NOT_FOUND",()->tradeFacts.read(actor().tenantId(),member));
        Actor foreignTenant=actor(); long foreignMember=Long.parseLong(members.register(foreignTenant,"其他租户会员"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->new TransactionTemplate(transactions).execute(status->
            tradeFacts.confirmPaidOrder(foreignTenant.tenantId(),foreignMember,id,100,"CNY")));
        assertFalse(tradeFacts.read(foreignTenant.tenantId(),foreignMember).everPaid());
        var fact=confirmFact(f,order);
        assertCode("PAYMENT_FACT_CONFLICT",()->new TransactionTemplate(transactions).execute(status->
            tradeFacts.confirmPaidOrder(f.actor().tenantId(),member,id,101,"CNY")));
        assertCode("PAYMENT_FACT_CONFLICT",()->new TransactionTemplate(transactions).execute(status->
            tradeFacts.confirmPaidOrder(f.actor().tenantId(),member,id,100,"USD")));
        assertEquals(fact,confirmFact(f,order));
        assertEquals(1,tradeFacts.read(f.actor().tenantId(),member).paidOrderCount());
    }

    @Test void frozenMemberStillAcceptsAuthoritativePaidFacts() {
        Fixture f=orderFixture(3); var order=factOrder(f,"frozen");
        long member=Long.parseLong(members.me(f.actor()).memberId());
        members.changeStatus(f.actor(),"freeze",member,0,"FROZEN","已发生资金仍须入账");
        assertTrue(confirmFact(f,order).firstConfirmedPaidOrder());
        assertEquals("FROZEN",members.me(f.actor()).status());
        assertTrue(members.me(f.actor()).everPaid());
    }

    @Test void rolledBackFirstCandidateDoesNotPreventConcurrentWinner() throws Exception {
        Fixture f=orderFixture(3); var one=factOrder(f,"rollback-first"); var two=factOrder(f,"winner");
        long member=Long.parseLong(members.me(f.actor()).memberId());
        var candidateWritten=new CountDownLatch(1); var contenderRead=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var candidate=workers.submit(()->new TransactionTemplate(transactions).execute(status->{
                var result=tradeFacts.confirmPaidOrder(f.actor().tenantId(),member,Long.parseLong(one.orderId()),100,"CNY");
                assertTrue(result.firstConfirmedPaidOrder()); candidateWritten.countDown();
                try { assertTrue(contenderRead.await(10,TimeUnit.SECONDS)); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                status.setRollbackOnly(); return result;
            }));
            var winner=workers.submit(()->{
                assertTrue(candidateWritten.await(10,TimeUnit.SECONDS));
                return new TransactionTemplate(transactions).execute(status->{
                    assertFalse(tradeFacts.read(f.actor().tenantId(),member).everPaid());
                    contenderRead.countDown();
                    return tradeFacts.confirmPaidOrder(f.actor().tenantId(),member,Long.parseLong(two.orderId()),100,"CNY");
                });
            });
            candidate.get(15,TimeUnit.SECONDS);
            assertTrue(winner.get(15,TimeUnit.SECONDS).firstConfirmedPaidOrder());
        }
        var result=tradeFacts.read(f.actor().tenantId(),member);
        assertEquals(two.orderId(),result.firstPaidOrderId()); assertEquals(1,result.paidOrderCount());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void paidFactDatabaseConstraintsRejectCorruption() {
        Fixture f=orderFixture(3); var one=factOrder(f,"first"); var two=factOrder(f,"second");
        confirmFact(f,one);
        assertMysqlConstraint(3819,()->jdbc.update(
            "UPDATE tc_member_trade_fact SET paid_order_count=0 WHERE tenant_id=?",f.actor().tenantId()));
        // 独立SQL断言数据库兜底，不能只以Service已校验为依据。
        String insert="INSERT INTO tc_member_paid_order(tenant_id,member_id,order_id,paid_amount,currency,confirmed_at,is_first,first_order_policy,fact_version,created_at,updated_at) "
            +"SELECT tenant_id,member_id,?,?,?,confirmed_at,?,?,?,created_at,updated_at FROM tc_member_paid_order WHERE tenant_id=? AND order_id=?";
        Object[][] invalid={
            {two.orderId(),100,"CNY",true,"FIRST_CONFIRMED_PAID_ORDER_V1",2},
            {two.orderId(),100,"CNY",false,"FIRST_CONFIRMED_PAID_ORDER_V1",1},
            {one.orderId(),100,"CNY",false,"FIRST_CONFIRMED_PAID_ORDER_V1",2},
            {two.orderId(),0,"CNY",false,"FIRST_CONFIRMED_PAID_ORDER_V1",2},
            {two.orderId(),9007199254740992L,"CNY",false,"FIRST_CONFIRMED_PAID_ORDER_V1",2},
            {two.orderId(),100,"cny",false,"FIRST_CONFIRMED_PAID_ORDER_V1",2},
            {two.orderId(),100,"CNY",false,"UNSUPPORTED",2}
        };
        for(int i=0;i<invalid.length;i++) {
            Object[] values=invalid[i];
            var args=new ArrayList<>(Arrays.asList(values)); args.add(f.actor().tenantId()); args.add(one.orderId());
            assertMysqlConstraint(i<3?1062:3819,()->jdbc.update(insert,args.toArray()));
        }
        assertEquals(1,count("SELECT COUNT(*) FROM tc_member_paid_order WHERE tenant_id=?",f.actor().tenantId()));
    }
    /** MySQL CHECK的SQLState为HY000，Spring可能不归类；直接验证供应商约束错误码。 */
    private void assertMysqlConstraint(int code,Runnable action) {
        var error=assertThrows(org.springframework.dao.DataAccessException.class,action::run);
        assertInstanceOf(java.sql.SQLException.class,error.getMostSpecificCause());
        assertEquals(code,((java.sql.SQLException)error.getMostSpecificCause()).getErrorCode());
    }

    private com.lrj.trade.order.model.OrderView factOrder(Fixture f,String key) {
        var quote=quotes.create(f.actor(),"quote-"+key,List.of(new QuoteSelection(Long.toString(f.sku()),1L)));
        return orders.create(f.actor(),"order-"+key,Long.parseLong(quote.quoteId()),f.warehouse(),shipping());
    }
    /** 仅测试内部端口前置；不是伪造真实渠道验收，收款全链路测试须在支付模块接入后补齐。 */
    private com.lrj.trade.member.model.PaidOrderFact confirmFact(Fixture f,com.lrj.trade.order.model.OrderView order) {
        return new TransactionTemplate(transactions).execute(status->tradeFacts.confirmPaidOrder(f.actor().tenantId(),
            Long.parseLong(members.me(f.actor()).memberId()),Long.parseLong(order.orderId()),order.payableAmount(),order.currency()));
    }

    @Test void physicalOrderFreezesQuoteAndEncryptsAddress() throws Exception {
        Fixture f=orderFixture(10);
        var quote=quotes.create(f.actor(),"quote",List.of(new QuoteSelection(Long.toString(f.sku()),2L)));
        long product=Long.parseLong(catalog.list(f.actor().tenantId(),0,10,true).getFirst().productId());
        catalog.unpublish(f.actor(),"off",product,1);
        catalog.edit(f.actor(),"edit",product,2,"新价格",500,"CNY");
        catalog.publish(f.actor(),"republish",product,3);
        var order=orders.create(f.actor(),"order",Long.parseLong(quote.quoteId()),f.warehouse(),shipping());
        assertEquals(200,order.payableAmount());
        assertEquals("PENDING_PAYMENT",order.status());
        assertEquals(8,balance(f).available()); assertEquals(2,balance(f).reserved());
        assertEquals(order,orders.create(f.actor(),"order",Long.parseLong(quote.quoteId()),f.warehouse(),shipping()));
        assertCode("IDEMPOTENCY_CONFLICT",()->orders.create(f.actor(),"order",Long.parseLong(quote.quoteId()),f.warehouse(),
            new com.lrj.trade.order.model.ShippingAddress("另一收件人","13800138000","地址")));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->orders.create(f.actor(),"different-key",Long.parseLong(quote.quoteId()),f.warehouse(),shipping()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_order WHERE tenant_id=?",f.actor().tenantId()));
        byte[] cipher=jdbc.queryForObject("SELECT recipient_cipher FROM tc_order_address WHERE tenant_id=?",byte[].class,f.actor().tenantId());
        assertFalse(new String(cipher,java.nio.charset.StandardCharsets.UTF_8).contains("测试收件人"));
        var decrypt=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(ADDRESS_TEST_KEY,"AES"),
            new javax.crypto.spec.GCMParameterSpec(128,Arrays.copyOf(cipher,12)));
        decrypt.updateAAD((f.actor().tenantId()+":"+order.orderNo()+":recipient").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("测试收件人",new String(decrypt.doFinal(Arrays.copyOfRange(cipher,12,cipher.length)),java.nio.charset.StandardCharsets.UTF_8));
        assertNull(order.paidAt()); assertFalse(members.me(f.actor()).everPaid());
        assertConservation(f);
    }

    @Test void multiSkuOrderFailureRollsBackAllReservationsAndSnapshots() {
        Fixture f=orderFixture(10);
        long other=publishedSku(f.actor(),"no-stock",200,"CNY");
        var quote=quotes.create(f.actor(),"quote",List.of(new QuoteSelection(Long.toString(f.sku()),2L),new QuoteSelection(Long.toString(other),1L)));
        assertCode("INSUFFICIENT_STOCK",()->orders.create(f.actor(),"order",Long.parseLong(quote.quoteId()),f.warehouse(),shipping()));
        for(String table:List.of("tc_order","tc_order_item","tc_order_address","tc_inventory_reservation"))
            assertEquals(0,count("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",f.actor().tenantId()),table);
        assertEquals(0,count("SELECT COUNT(*) FROM tc_command WHERE tenant_id=? AND operation IN ('order.create','inventory.reserve')",f.actor().tenantId()));
        assertEquals(10,balance(f).available()); assertEquals(0,balance(f).reserved());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_inventory_ledger WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void orderCancelAndPaymentPermissionSerializeOnSameRow() throws Exception {
        Fixture f=orderFixture(10);
        var quote=quotes.create(f.actor(),"quote",List.of(new QuoteSelection(Long.toString(f.sku()),1L)));
        var order=orders.create(f.actor(),"order",Long.parseLong(quote.quoteId()),f.warehouse(),shipping());
        var sequence=new AtomicLong();
        parallel(20,()->{
            if(sequence.incrementAndGet()%2==0) {
                try { new TransactionTemplate(transactions).execute(status->orders.authorizePayment(f.actor(),order.orderNo())); }
                catch(TradeException conflict) { assertEquals("STATE_CONFLICT",conflict.code()); }
            } else orders.cancel(f.actor(),"cancel-"+sequence.get(),order.orderNo(),"测试取消");
            return true;
        });
        var current=orders.read(f.actor(),order.orderNo());
        assertTrue(Set.of("CANCELLED","CLOSING").contains(current.status()));
        if(current.status().equals("CANCELLED")) { assertEquals(10,balance(f).available()); assertEquals(0,balance(f).reserved()); }
        else { assertEquals(9,balance(f).available()); assertEquals(1,balance(f).reserved()); }
        assertCode("STATE_CONFLICT",()->new TransactionTemplate(transactions).execute(status->orders.authorizePayment(f.actor(),order.orderNo())));
        assertConservation(f);
    }

    @Test void pendingPaymentCancelNeverReleasesStockAndNoPaymentCancelDoes() {
        Fixture f=orderFixture(10);
        var q1=quotes.create(f.actor(),"q1",List.of(new QuoteSelection(Long.toString(f.sku()),2L)));
        var first=orders.create(f.actor(),"o1",Long.parseLong(q1.quoteId()),f.warehouse(),shipping());
        assertEquals("CANCELLED",orders.cancel(f.actor(),"c1",first.orderNo(),"无支付尝试").status());
        assertEquals(10,balance(f).available());
        var q2=quotes.create(f.actor(),"q2",List.of(new QuoteSelection(Long.toString(f.sku()),3L)));
        var second=orders.create(f.actor(),"o2",Long.parseLong(q2.quoteId()),f.warehouse(),shipping());
        new TransactionTemplate(transactions).execute(status->orders.authorizePayment(f.actor(),second.orderNo()));
        when(clock.instant()).thenReturn(second.expiresAt().plusSeconds(60));
        assertEquals("CLOSING",orders.cancel(f.actor(),"c2",second.orderNo(),"支付结果未决").status());
        assertEquals(7,balance(f).available()); assertEquals(3,balance(f).reserved());
        assertEquals("CLOSING",orders.cancel(f.actor(),"c3",second.orderNo(),"再试取消").status());
        assertConservation(f);
    }

    @Test void serviceZeroOrderNeedsNoAddressAndNeverBecomesFirstPaidOrder() {
        Actor actor=actor(); members.register(actor,"服务会员"); merchants.create(actor,"merchant","logical","商户");
        long product=Long.parseLong(catalog.create(actor,"p","p","免费服务","s",0,"CNY"));
        long sku=Long.parseLong(catalog.detail(actor.tenantId(),product,true).skuId());
        skus.edit(actor,"service",product,sku,0,0,"CNY","SERVICE",Map.of());
        catalog.publish(actor,"publish",product,1);
        var quote=quotes.create(actor,"quote",List.of(new QuoteSelection(Long.toString(sku),1L)));
        var order=orders.create(actor,"order",Long.parseLong(quote.quoteId()),null,null);
        assertEquals("PAID",order.status()); assertEquals("NO_PAYMENT_REQUIRED",order.financialStatus());
        assertNull(order.paidAt()); assertFalse(members.me(actor).everPaid());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='ORDER_RESOLVE'",actor.tenantId()));
        long cursor=orderBackfill.enqueuePage(actor.tenantId(),0,1);
        assertEquals(Long.parseLong(order.orderId()),cursor);
        assertEquals(cursor,orderBackfill.enqueuePage(actor.tenantId(),cursor,1));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND job_type='ORDER_RESOLVE'",actor.tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_order_address WHERE tenant_id=?",actor.tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_inventory_reservation WHERE tenant_id=?",actor.tenantId()));
        assertCode("STATE_CONFLICT",()->new TransactionTemplate(transactions).execute(status->orders.authorizePayment(actor,order.orderNo())));
        var source=new Actor(actor.tenantId(),"https://service.test","fulfillment-service");
        fulfillment.deliver(source,"delivered",order.orderNo(),"proof:service-delivered",clock.instant());
        fulfillment.complete(source,"completed",order.orderNo(),"proof:service-complete",clock.instant());
        var zeroEvidence=referralEvidence.query(actor.tenantId(),List.of(Long.parseLong(order.orderId()))).getFirst().evidence();
        assertEquals(0,zeroEvidence.paidAmount()); assertFalse(zeroEvidence.isFirstConfirmedPaidOrder()); assertNull(zeroEvidence.paidAt()); assertEquals(clock.instant(),zeroEvidence.completedAt());
        assertCode("RETURN_INVALID",()->returns.inspect(source,"service-return",order.orderNo(),Long.parseLong(order.items().getFirst().itemId()),1,1,1,"proof"));
        assertEquals("COMPLETED",orders.read(actor,order.orderNo()).status());
        assertFalse(members.me(actor).everPaid()); assertNull(orders.read(actor,order.orderNo()).paidAt());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-paid.v1'",actor.tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=? AND event_type='trade.order-completed.v1'",actor.tenantId()));
    }

    @Test void orderOwnershipFrozenMerchantAndExpiredQuoteFailClosed() {
        Fixture f=orderFixture(10);
        var quote=quotes.create(f.actor(),"quote",List.of(new QuoteSelection(Long.toString(f.sku()),1L)));
        merchants.changeStatus(f.actor(),"freeze-merchant",0,"FROZEN","暂停新单");
        assertCode("MERCHANT_UNAVAILABLE",()->orders.create(f.actor(),"order",Long.parseLong(quote.quoteId()),f.warehouse(),shipping()));
        merchants.changeStatus(f.actor(),"activate",1,"ACTIVE","恢复");
        var order=orders.create(f.actor(),"order",Long.parseLong(quote.quoteId()),f.warehouse(),shipping());
        Actor other=new Actor(f.actor().tenantId(),f.actor().issuer(),"other-order-member"); members.register(other,"其他会员");
        assertCode("NOT_FOUND",()->orders.read(other,order.orderNo()));
        assertCode("NOT_FOUND",()->orders.cancel(other,"cancel",order.orderNo(),"越权"));
        assertTrue(orders.list(other,0,100).isEmpty());
        var second=quotes.create(f.actor(),"expired",List.of(new QuoteSelection(Long.toString(f.sku()),1L)));
        when(clock.instant()).thenReturn(second.expiresAt());
        assertCode("QUOTE_EXPIRED",()->orders.create(f.actor(),"late",Long.parseLong(second.quoteId()),f.warehouse(),shipping()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_order WHERE tenant_id=?",f.actor().tenantId()));
    }

    private Fixture orderFixture(long stock) {
        Fixture f=fixture(stock); members.register(f.actor(),"订单会员");
        merchants.create(f.actor(),"merchant","logical-merchant","测试商户");
        return f;
    }
    @Test void oneHundredOrderReplaysHaveOneBusinessEffect() throws Exception {
        Fixture f=orderFixture(10);
        var quote=quotes.create(f.actor(),"quote",List.of(new QuoteSelection(Long.toString(f.sku()),2L)));
        var results=parallel(100,()->orders.create(f.actor(),"order",Long.parseLong(quote.quoteId()),f.warehouse(),shipping()));
        assertEquals(1,new HashSet<>(results).size());
        for(String table:List.of("tc_order","tc_order_item","tc_order_address","tc_inventory_reservation"))
            assertEquals(1,count("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",f.actor().tenantId()),table);
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='order.create'",f.actor().tenantId()));
        assertEquals(8,balance(f).available()); assertEquals(2,balance(f).reserved());
    }

    @Test void merchantAndOrderHttpFlowPreservesOwnershipAndHidesAddress() throws Exception {
        Fixture f=fixture(10);
        jwt("order-http-admin","trade.admin",f.actor().tenantId());
        jwt("order-http-member","",f.actor().tenantId());
        Actor buyer=new Actor(f.actor().tenantId(),"https://identity.test","order-http-member");
        members.register(buyer,"订单HTTP会员");
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        String merchantBody=json.writeValueAsString(Map.of("merchantNo","http-merchant","name","测试商户"));
        assertEquals(403,request("POST","/api/v1/trade/admin/merchant","order-http-member",merchantBody,"m").statusCode());
        assertEquals(200,request("POST","/api/v1/trade/admin/merchant","order-http-admin",merchantBody,"m").statusCode());
        assertEquals(200,request("GET","/api/v1/trade/admin/merchant","order-http-admin",null).statusCode());
        var quote=quotes.create(buyer,"quote",List.of(new QuoteSelection(Long.toString(f.sku()),1L)));
        var input=new LinkedHashMap<String,Object>();
        input.put("quoteId",quote.quoteId()); input.put("warehouseId",Long.toString(f.warehouse())); input.put("address",shipping());
        input.put("payableAmount",1);
        assertEquals(400,request("POST","/api/v1/trade/orders","order-http-member",json.writeValueAsString(input),"bad").statusCode());
        input.remove("payableAmount");
        var response=request("POST","/api/v1/trade/orders","order-http-member",json.writeValueAsString(input),"order");
        assertEquals(200,response.statusCode(),response.body());
        assertFalse(response.body().contains("测试收件人")); assertFalse(response.body().contains("13800138000"));
        String number=json.readTree(response.body()).path("orderNo").asText();
        assertEquals(200,request("GET","/api/v1/trade/orders/"+number,"order-http-member",null).statusCode());
        assertEquals(1,json.readTree(request("GET","/api/v1/trade/orders","order-http-member",null).body()).size());
        var cancelled=request("POST","/api/v1/trade/orders/"+number+":cancel","order-http-member",json.writeValueAsString(Map.of("reason","用户取消")),"cancel");
        assertEquals(200,cancelled.statusCode(),cancelled.body());
        assertEquals("CANCELLED",json.readTree(cancelled.body()).path("status").asText());
        assertEquals(10,balance(f).available());
        assertEquals(200,request("POST","/api/v1/trade/admin/merchant:change-status","order-http-admin",
            json.writeValueAsString(Map.of("expectedVersion",0,"targetStatus","FROZEN","reason","暂停")),"freeze").statusCode());
    }
    private com.lrj.trade.order.model.ShippingAddress shipping() {
        return new com.lrj.trade.order.model.ShippingAddress("测试收件人","13800138000","测试地址一号");
    }

    @Test void quoteIsConcurrentIdempotentAndCanonicalBySku() throws Exception {
        Fixture f=fixture(10);
        members.register(f.actor(),"报价会员");
        long other=publishedSku(f.actor(),"other",250,"CNY");
        var first=List.of(new QuoteSelection(Long.toString(f.sku()),2L),new QuoteSelection(Long.toString(other),3L));
        var results=parallel(30,()->quotes.create(f.actor(),"quote",first));
        assertEquals(1,new HashSet<>(results).size());
        var quote=results.getFirst();
        assertEquals(950,quote.payableAmount());
        assertEquals("FIXED_PRICE_V1",quote.pricingPolicy());
        assertEquals(quote,quotes.create(f.actor(),"quote",List.of(first.get(1),first.get(0))));
        assertCode("IDEMPOTENCY_CONFLICT",()->quotes.create(f.actor(),"quote",List.of(new QuoteSelection(Long.toString(f.sku()),1L))));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_quote WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(2,count("SELECT COUNT(*) FROM tc_quote_item WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='quote.create'",f.actor().tenantId()));
        assertEquals(10,balance(f).available());
        assertEquals(0,balance(f).reserved());
    }

    @Test void quoteUsesFrozenPriceAndExactExpiryBoundary() {
        Fixture f=fixture(10); members.register(f.actor(),"报价会员");
        var input=List.of(new QuoteSelection(Long.toString(f.sku()),2L));
        var quote=quotes.create(f.actor(),"quote",input);
        long id=Long.parseLong(quote.quoteId());
        long product=Long.parseLong(catalog.list(f.actor().tenantId(),0,10,true).getFirst().productId());
        catalog.unpublish(f.actor(),"off",product,1);
        assertCode("NOT_FOUND",()->new TransactionTemplate(transactions).execute(status->quotes.requireUsable(f.actor(),id)));
        catalog.edit(f.actor(),"edit",product,2,"新名称",300,"CNY");
        catalog.publish(f.actor(),"republish",product,3);
        when(clock.instant()).thenReturn(quote.expiresAt().minusMillis(1));
        assertEquals(200,new TransactionTemplate(transactions).execute(status->quotes.requireUsable(f.actor(),id)).payableAmount());
        when(clock.instant()).thenReturn(quote.expiresAt());
        assertCode("QUOTE_EXPIRED",()->new TransactionTemplate(transactions).execute(status->quotes.requireUsable(f.actor(),id)));
        assertEquals(quote,quotes.create(f.actor(),"quote",input));
        var next=quotes.create(f.actor(),"fresh",input);
        assertEquals(600,next.payableAmount());
        assertEquals(quote.expiresAt().plusSeconds(300),next.expiresAt());
        assertEquals("商品",quotes.read(f.actor(),id).items().getFirst().title());
    }

    @Test void quoteOwnershipAndFrozenMemberAreEnforced() {
        Fixture f=fixture(10);
        var input=List.of(new QuoteSelection(Long.toString(f.sku()),1L));
        assertCode("NOT_FOUND",()->quotes.create(f.actor(),"unregistered",input));
        long member=Long.parseLong(members.register(f.actor(),"报价会员"));
        var quote=quotes.create(f.actor(),"quote",input);
        Actor other=new Actor(f.actor().tenantId(),f.actor().issuer(),"another");
        members.register(other,"同租户另一会员");
        Actor tenant=new Actor(f.actor().tenantId()+10000,f.actor().issuer(),f.actor().subject());
        members.register(tenant,"跨租户会员");
        assertCode("NOT_FOUND",()->quotes.read(other,Long.parseLong(quote.quoteId())));
        assertCode("NOT_FOUND",()->quotes.read(tenant,Long.parseLong(quote.quoteId())));
        members.changeStatus(f.actor(),"freeze",member,0,"FROZEN","测试冻结");
        assertCode("MEMBER_FROZEN",()->quotes.create(f.actor(),"new",input));
        assertCode("MEMBER_FROZEN",()->new TransactionTemplate(transactions).execute(status->quotes.requireUsable(f.actor(),Long.parseLong(quote.quoteId()))));
        assertEquals(quote,quotes.create(f.actor(),"quote",input));
    }

    @Test void invalidQuoteAmountsAndSelectionsLeaveNoPartialRows() {
        Fixture f=fixture(10); members.register(f.actor(),"报价会员");
        String sku=Long.toString(f.sku());
        assertCode("INVALID_ARGUMENT",()->quotes.create(f.actor(),"null",null));
        assertCode("INVALID_ARGUMENT",()->quotes.create(f.actor(),"empty",List.of()));
        assertCode("INVALID_ARGUMENT",()->quotes.create(f.actor(),"duplicate",List.of(new QuoteSelection(sku,1L),new QuoteSelection(sku,2L))));
        for(Long quantity:Arrays.asList(null,0L,-1L,1000000001L))
            assertCode("INVALID_ARGUMENT",()->quotes.create(f.actor(),"qty",List.of(new QuoteSelection(sku,quantity))));
        for(String invalid:List.of("0","-1","01","9223372036854775808","not-id"))
            assertCode("INVALID_ARGUMENT",()->quotes.create(f.actor(),"sku",List.of(new QuoteSelection(invalid,1L))));
        assertCode("INVALID_ARGUMENT",()->quotes.create(f.actor(),"many",Collections.nCopies(51,new QuoteSelection(sku,1L))));
        long usd=publishedSku(f.actor(),"usd",100,"USD");
        assertCode("INVALID_ARGUMENT",()->quotes.create(f.actor(),"currency",List.of(new QuoteSelection(sku,1L),new QuoteSelection(Long.toString(usd),1L))));
        long expensive=publishedSku(f.actor(),"expensive",9007199254740991L,"CNY");
        assertCode("INVALID_ARGUMENT",()->quotes.create(f.actor(),"line-overflow",List.of(new QuoteSelection(Long.toString(expensive),1000000000L))));
        assertCode("INVALID_ARGUMENT",()->quotes.create(f.actor(),"sum-overflow",List.of(new QuoteSelection(sku,1L),new QuoteSelection(Long.toString(expensive),1L))));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_quote WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_quote_item WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_command WHERE tenant_id=? AND operation='quote.create'",f.actor().tenantId()));
    }

    private long publishedSku(Actor actor,String number,long price,String currency) {
        long product=Long.parseLong(catalog.create(actor,"create-"+number,number,number,number,price,currency));
        catalog.publish(actor,"publish-"+number,product,0);
        return Long.parseLong(catalog.detail(actor.tenantId(),product,true).skuId());
    }

    @Test void quoteSnapshotAndCommandRollbackWithCaller() {
        Fixture f=fixture(10); members.register(f.actor(),"报价会员");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            quotes.create(f.actor(),"quote",List.of(new QuoteSelection(Long.toString(f.sku()),1L)));
            throw new IllegalStateException("模拟调用方失败");
        }));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_quote WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_quote_item WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_command WHERE tenant_id=? AND operation='quote.create'",f.actor().tenantId()));
        assertEquals(0,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=? AND action='quote.create'",f.actor().tenantId()));
    }

    @Test void skuAndQuoteHttpContractRejectsTamperingAndFractionalQuantities() throws Exception {
        Actor actor=actor();
        jwt("sku-admin","trade.admin",actor.tenantId());
        jwt("quote-buyer","",actor.tenantId());
        jwt("quote-other","",actor.tenantId());
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        long product=Long.parseLong(catalog.create(actor,"product","p","商品","primary",100,"CNY"));
        String route="/api/v1/trade/admin/products/"+product+"/skus";
        var skuBody=new LinkedHashMap<String,Object>();
        skuBody.put("expectedVersion",0); skuBody.put("skuNo","second"); skuBody.put("unitPrice",250);
        skuBody.put("currency","CNY"); skuBody.put("stockType","SERVICE"); skuBody.put("specification",Map.of("颜色","红色"));
        String body=json.writeValueAsString(skuBody);
        assertEquals(403,request("POST",route,"quote-buyer",body,"denied").statusCode());
        var added=request("POST",route,"sku-admin",body,"add");
        assertEquals(200,added.statusCode(),added.body());
        String sku=json.readTree(added.body()).path("resourceId").asText();
        assertEquals(2,json.readTree(request("GET",route,"sku-admin",null).body()).size());
        assertEquals(0,json.readTree(request("GET","/api/v1/trade/products/"+product+"/skus","quote-buyer",null).body()).size());
        skuBody.remove("skuNo"); skuBody.put("expectedVersion",1); skuBody.put("unitPrice",375);
        assertEquals(200,request("PATCH",route+"/"+sku,"sku-admin",json.writeValueAsString(skuBody),"edit").statusCode());
        assertEquals(412,request("PATCH",route+"/"+sku,"sku-admin",json.writeValueAsString(skuBody),"stale").statusCode());
        catalog.publish(actor,"publish",product,2);
        var published=request("GET","/api/v1/trade/products/"+product+"/skus","quote-buyer",null);
        assertEquals(200,published.statusCode());
        assertEquals(375,json.readTree(published.body()).get(1).path("unitPrice").asLong());
        assertEquals(200,request("POST","/api/v1/trade/members:register","quote-buyer",json.writeValueAsString(Map.of("displayName","买家"))).statusCode());
        var selection=Map.of("items",List.of(Map.of("skuId",sku,"quantity",2)));
        String input=json.writeValueAsString(selection);
        String quotesRoute="/api/v1/trade/quotes";
        var created=request("POST",quotesRoute,"quote-buyer",input,"quote");
        assertEquals(200,created.statusCode(),created.body());
        var quote=json.readTree(created.body());
        assertEquals(750,quote.path("payableAmount").asLong());
        assertEquals("SERVICE",quote.path("items").get(0).path("stockType").asText());
        assertEquals(created.body(),request("POST",quotesRoute,"quote-buyer",input,"quote").body());
        assertEquals(created.body(),request("GET",quotesRoute+"/"+quote.path("quoteId").asText(),"quote-buyer",null).body());
        assertEquals(400,request("POST",quotesRoute,"quote-buyer",input).statusCode());
        assertEquals(400,request("POST",quotesRoute,"quote-buyer",json.writeValueAsString(Map.of("items",selection.get("items"),"payableAmount",1)),"amount").statusCode());
        assertEquals(400,request("POST",quotesRoute,"quote-buyer",json.writeValueAsString(Map.of("items",List.of(Map.of("skuId",sku,"quantity",1.5)))),"fraction").statusCode());
        assertEquals(400,request("POST",quotesRoute,"quote-buyer",json.writeValueAsString(Map.of("items",List.of(Map.of("skuId",sku,"quantity",1,"unitPrice",1)))),"price").statusCode());
        assertEquals(200,request("POST","/api/v1/trade/members:register","quote-other",json.writeValueAsString(Map.of("displayName","其他人"))).statusCode());
        assertEquals(404,request("GET",quotesRoute+"/"+quote.path("quoteId").asText(),"quote-other",null).statusCode());
    }

    @Test void mapperCurrentReadsDoNotUseSessionCache() {
        Actor actor=actor();
        long id=Long.parseLong(members.register(actor,"当前读"));
        assertEquals(org.apache.ibatis.session.LocalCacheScope.STATEMENT,sqlSessions.getConfiguration().getLocalCacheScope());
        assertFalse(sqlSessions.getConfiguration().isCacheEnabled());
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            assertEquals(0,memberMapper.lockById(actor.tenantId(),id).getFirst().version());
            // 直接JDBC模拟同一连接中其他持久化适配器的写入，不触发MyBatis缓存失效。
            jdbc.update("UPDATE tc_member SET version=version+1 WHERE tenant_id=? AND id=?",actor.tenantId(),id);
            assertEquals(1,memberMapper.lockById(actor.tenantId(),id).getFirst().version());
            status.setRollbackOnly();
        });
        assertEquals(0,members.me(actor).version());
    }

    @Test void mapperCommandsAuditAndOutboxJoinOuterTransaction() {
        Actor actor=actor();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            members.register(actor,"回滚注册");
            members.updateProfile(actor,"profile",0,"应回滚");
            throw new IllegalStateException("模拟组合事务失败");
        }));
        for(String table:List.of("tc_command","tc_member","tc_member_trade_fact","tc_operation_audit","tc_outbox"))
            assertEquals(0L,count("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",actor.tenantId()),table);
        members.register(actor,"重试注册");
        assertEquals("重试注册",members.me(actor).displayName());
    }

    @Test void mapperResultMappingsPreserveUtcNullsAndBoundText() {
        Actor actor=actor();
        String text="中文 ' OR 1=1 -- 引用:值";
        members.register(actor,text);
        jdbc.update("UPDATE tc_member SET registered_at='2026-09-07 00:01:02.123' WHERE tenant_id=?",actor.tenantId());
        assertEquals(text,members.me(actor).displayName());
        assertEquals("2026-09-07T00:01:02.123Z",members.me(actor).registeredAt());
        String product=catalog.create(actor,"create","p",text,"s",9007199254740991L,"CNY");
        var draft=catalog.detail(actor.tenantId(),Long.parseLong(product),true);
        assertNull(draft.publishedRevision());
        assertEquals(9007199254740991L,draft.unitPrice());
        assertEquals(text,draft.name());
        assertCode("NOT_FOUND",()->members.me(new Actor(actor.tenantId(),actor.issuer(),"not-owned")));
    }

    @Test void mapperSkuDraftAndSnapshotMappingsStaySeparate() {
        Actor actor=actor();
        long product=Long.parseLong(catalog.create(actor,"create","p","商品","primary",100,"CNY"));
        String sku=skus.add(actor,"add",product,0,"second",250,"CNY","SERVICE",Map.of("颜色","红色"));
        assertEquals(2,skus.list(actor.tenantId(),product,0,10,true).size());
        assertTrue(skus.list(actor.tenantId(),product,0,10,false).isEmpty());
        assertEquals(1,catalog.list(actor.tenantId(),0,10,true).size());
        catalog.publish(actor,"publish",product,1);
        var snapshot=new TransactionTemplate(transactions).execute(status->skus.published(actor.tenantId(),Long.parseLong(sku)));
        assertEquals(Map.of("颜色","红色"),snapshot.specification());
        assertEquals("SERVICE",snapshot.stockType());
        assertEquals(250,snapshot.unitPrice());
        assertEquals(2,snapshot.revision());
        assertEquals(snapshot,skus.list(actor.tenantId(),product,0,10,false).get(1));
        catalog.unpublish(actor,"offline",product,2);
        skus.edit(actor,"edit",product,Long.parseLong(sku),3,300,"CNY","PHYSICAL",Map.of("颜色","蓝色"));
        assertEquals(100,catalog.detail(actor.tenantId(),product,true).unitPrice());
        assertEquals(300,skus.list(actor.tenantId(),product,0,10,true).get(1).unitPrice());
        assertEquals(250L,jdbc.queryForObject("SELECT unit_price FROM tc_sku_revision WHERE tenant_id=? AND sku_id=? AND revision=2",Long.class,actor.tenantId(),sku));
        assertTrue(skus.list(actor.tenantId()+10000,product,0,10,true).isEmpty());
    }

    @Test void registrationIsConcurrentAndTenantIsolated() throws Exception {
        Actor a=actor();
        var ids=parallel(30,()->members.register(a,"测试会员"));
        assertEquals(1,new HashSet<>(ids).size());
        assertEquals(ids.getFirst(),members.me(a).memberId());
        assertFalse(members.me(a).everPaid());
        assertNotEquals(ids.getFirst(),members.register(new Actor(a.tenantId()+10000,a.issuer(),a.subject()),"另一个租户"));
        assertEquals(1L,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=?",a.tenantId()));
    }

    @Test void productSnapshotsAreImmutableAndCommandsDetectConflicts() {
        Actor a=actor();
        String p=catalog.create(a,"create","p","原名称","s",100,"CNY");
        assertEquals(p,catalog.create(a,"create","p","原名称","s",100,"CNY"));
        assertCode("IDEMPOTENCY_CONFLICT",()->catalog.create(a,"create","p","新名称","s",100,"CNY"));
        assertTrue(catalog.list(a.tenantId(),0,20,false).isEmpty());
        catalog.publish(a,"pub",Long.parseLong(p),0);
        catalog.unpublish(a,"off",Long.parseLong(p),1);
        catalog.edit(a,"edit",Long.parseLong(p),2,"新名称",200,"CNY");
        catalog.publish(a,"pub2",Long.parseLong(p),3);
        assertEquals(List.of(100L,200L),jdbc.queryForList("SELECT unit_price FROM tc_sku_revision WHERE tenant_id=? ORDER BY revision",Long.class,a.tenantId()));
        assertCode("VERSION_CONFLICT",()->catalog.unpublish(a,"stale",Long.parseLong(p),1));
        assertTrue(catalog.list(a.tenantId()+1,0,20,true).isEmpty());
    }

    @Test void inventoryCannotOversellUnderConcurrentReservations() throws Exception {
        Fixture f=fixture(20);
        AtomicLong keys=new AtomicLong();
        var results=parallel(80,()->{
            try { inventory.reserve(f.actor(),"r-"+keys.incrementAndGet(),f.warehouse(),f.sku(),1); return true; }
            catch(TradeException expected) { assertEquals("INSUFFICIENT_STOCK",expected.code()); return false; }
        });
        assertEquals(20,results.stream().filter(Boolean::booleanValue).count());
        var b=balance(f);
        assertEquals(0,b.available()); assertEquals(20,b.reserved());
        assertConservation(f);
    }

    @Test void confirmationAndReleaseCompeteForOneTerminalState() throws Exception {
        Fixture f=fixture(10);
        inventory.reserve(f.actor(),"r",f.warehouse(),f.sku(),4);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->terminal(()->inventory.confirm(f.actor(),"confirm","r")));
            var second=pool.submit(()->terminal(()->inventory.release(f.actor(),"release","r")));
            assertEquals(1,(first.get()?1:0)+(second.get()?1:0));
        }
        var b=balance(f);
        assertEquals(0,b.reserved()); assertTrue(b.sold()==4 || b.available()==10);
        assertConservation(f);
    }

    @Test void duplicateAdjustmentAndReservationHaveOneEffect() {
        Fixture f=fixture(10);
        String op=inventory.adjust(f.actor(),"same",f.warehouse(),f.sku(),5,"到货");
        assertEquals(op,inventory.adjust(f.actor(),"same",f.warehouse(),f.sku(),5,"到货"));
        assertCode("IDEMPOTENCY_CONFLICT",()->inventory.adjust(f.actor(),"same",f.warehouse(),f.sku(),6,"到货"));
        String r=inventory.reserve(f.actor(),"r",f.warehouse(),f.sku(),3);
        assertEquals(r,inventory.reserve(f.actor(),"r",f.warehouse(),f.sku(),3));
        inventory.confirm(f.actor(),"c1","r"); inventory.confirm(f.actor(),"c2","r");
        assertEquals(15,balance(f).stockTotal()); assertEquals(3,balance(f).sold());
        assertCode("STATE_CONFLICT",()->inventory.release(f.actor(),"release","r"));
        assertConservation(f);
    }

    @Test void outerOrderTransactionRollsBackAllReservationEffects() {
        Fixture f=fixture(10);
        long before=count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=?",f.actor().tenantId());
        assertThrows(TradeException.class,()->new TransactionTemplate(transactions).execute(status->{
            inventory.reserve(f.actor(),"first",f.warehouse(),f.sku(),4);
            inventory.reserve(f.actor(),"second",f.warehouse(),f.sku(),20);
            return null;
        }));
        assertEquals(10,balance(f).available());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_inventory_reservation WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(before,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=?",f.actor().tenantId()));
    }

    @Test void crossTenantWarehouseAndSkuCannotBeUsed() {
        Fixture f=fixture(10);
        Actor outsider=actor();
        assertCode("NOT_FOUND",()->inventory.adjust(outsider,"attack",f.warehouse(),f.sku(),2,"越权"));
        assertTrue(inventory.balances(outsider.tenantId(),0,20).isEmpty());
        assertEquals(10,balance(f).available());
    }

    @Test void missingAndDraftSkuCannotBeReserved() {
        Actor a=actor();
        catalog.create(a,"p","p","未发布","s",0,"CNY");
        long sku=Long.parseLong(catalog.list(a.tenantId(),0,20,true).getFirst().skuId());
        long wh=Long.parseLong(inventory.warehouse(a,"w","w","仓库"));
        inventory.adjust(a,"a",wh,sku,10,"初始化");
        assertCode("NOT_FOUND",()->inventory.reserve(a,"r",wh,sku,1));
        assertCode("INVALID_ARGUMENT",()->inventory.reserve(a,"r2",wh,sku,0));
        assertCode("INSUFFICIENT_STOCK",()->inventory.adjust(a,"a2",wh,sku,-11,"盘亏"));
    }

    @Test void schemaHasCommentsAndConservationConstraint() {
        assertEquals(0,count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_comment=''"));
        assertEquals(0,count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_comment=''"));
        Fixture f=fixture(10);
        var violation=assertThrows(org.springframework.dao.DataAccessException.class,
            ()->jdbc.update("UPDATE tc_inventory_balance SET available=-1 WHERE tenant_id=?",f.actor().tenantId()));
        assertInstanceOf(java.sql.SQLException.class,violation.getMostSpecificCause());
        assertEquals(3819,((java.sql.SQLException)violation.getMostSpecificCause()).getErrorCode());
        assertEquals(10,balance(f).available());
    }

    @Test void httpRequiresAuthenticationAndAdminScope() throws Exception {
        assertEquals(401,request("GET","/api/v1/trade/products",null,null).statusCode());
        jwt("member","",900);
        assertEquals(403,request("GET","/api/v1/trade/admin/products","member",null).statusCode());
        jwt("admin","trade.admin",900);
        assertEquals(200,request("GET","/api/v1/trade/admin/products","admin",null).statusCode());
        assertEquals(400,request("POST","/api/v1/trade/admin/products","admin","{}").statusCode());
        assertEquals(200,request("POST","/api/v1/trade/members:register","member","{\"displayName\":\"测试\"}").statusCode());
        assertEquals(200,request("GET","/api/v1/trade/members/me","member",null).statusCode());
    }

    @Test void invalidTenantClaimIsRejected() throws Exception {
        when(decoder.decode("bad")).thenReturn(Jwt.withTokenValue("bad").header("alg","RS256").issuer("https://identity.test").subject("u").build());
        assertEquals(403,request("GET","/api/v1/trade/products","bad",null).statusCode());
    }

    @Test void httpProductWarehouseAndInventoryWorkflow() throws Exception {
        long tenant=TENANTS.incrementAndGet();
        jwt("operator","trade.admin",tenant);
        String body="{\"productNo\":\"P\",\"name\":\"商品\",\"skuNo\":\"S\",\"unitPrice\":100,\"currency\":\"CNY\"}";
        var created=request("POST","/api/v1/trade/admin/products","operator",body,"create");
        assertEquals(200,created.statusCode(),created.body());
        assertEquals(created.body(),request("POST","/api/v1/trade/admin/products","operator",body,"create").body());
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        String id=json.readTree(created.body()).path("resourceId").asText();
        assertEquals(400,request("POST","/api/v1/trade/admin/products/"+id+":publish","operator","{}","missing-version").statusCode());
        assertEquals(200,request("POST","/api/v1/trade/admin/products/"+id+":publish","operator","{\"expectedVersion\":0}","publish").statusCode());
        var list=json.readTree(request("GET","/api/v1/trade/products","operator",null).body());
        String sku=list.get(0).path("skuId").asText();
        var wh=request("POST","/api/v1/trade/admin/warehouses","operator","{\"warehouseNo\":\"W\",\"name\":\"仓库\"}","warehouse");
        assertEquals(200,wh.statusCode(),wh.body());
        String warehouse=json.readTree(wh.body()).path("resourceId").asText();
        var adjusted=request("POST","/api/v1/trade/admin/inventory-adjustments","operator",
            "{\"warehouseId\":"+warehouse+",\"skuId\":"+sku+",\"delta\":10,\"reason\":\"到货\"}","stock");
        assertEquals(200,adjusted.statusCode(),adjusted.body());
        assertEquals(10,json.readTree(request("GET","/api/v1/trade/admin/inventories","operator",null).body()).get(0).path("available").asLong());
        assertEquals(400,request("POST","/api/v1/trade/admin/products","operator",body.replace("\"unitPrice\":100,",""),"missing-price").statusCode());
        assertEquals(400,request("POST","/api/v1/trade/members:register","operator","{\"displayName\":\"x\",\"tenant_id\":\"999\"}").statusCode());
    }

    @Test void paginationAndMoneyHaveExplicitBounds() {
        Actor a=actor();
        assertCode("INVALID_ARGUMENT",()->catalog.list(a.tenantId(),0,101,true));
        assertCode("INVALID_ARGUMENT",()->catalog.create(a,"c","p","p","s",9007199254740992L,"CNY"));
        assertCode("INVALID_ARGUMENT",()->catalog.create(a,"c","p","p","s",100,"cny"));
        assertCode("INVALID_ARGUMENT",()->inventory.balances(a.tenantId(),-1,20));
    }

    @Test void developmentSeedIsPersistedAndRepeatable() {
        DevSeed seed=new DevSeed(seedDatabaseMapper,members,catalog,inventory);
        seed.run(new org.springframework.boot.DefaultApplicationArguments());
        seed.run(new org.springframework.boot.DefaultApplicationArguments());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_member WHERE tenant_id=900001"));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_product WHERE tenant_id=900001"));
        assertEquals(100,inventory.balances(900001,0,20).getFirst().available());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_inventory_ledger WHERE tenant_id=900001"));
    }

    @Test void profileUpdatesAreVersionedAndIdempotent() {
        Actor a=actor();
        String member=members.register(a,"原名称");
        assertEquals(member,members.updateProfile(a,"profile",0,"新名称"));
        assertEquals(member,members.updateProfile(a,"profile",0,"新名称"));
        assertEquals("新名称",members.me(a).displayName()); assertEquals(1,members.me(a).version());
        assertCode("IDEMPOTENCY_CONFLICT",()->members.updateProfile(a,"profile",0,"其他名称"));
        assertCode("VERSION_CONFLICT",()->members.updateProfile(a,"stale",0,"其他名称"));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=?",a.tenantId()));
    }

    @Test void concurrentProfileChangesHaveOneWinner() throws Exception {
        Actor a=actor(); members.register(a,"原名称");
        var results=parallel(20,()->{
            try { members.updateProfile(a,UUID.randomUUID().toString(),0,"名称"); return true; }
            catch(TradeException conflict) { assertEquals("VERSION_CONFLICT",conflict.code()); return false; }
        });
        assertEquals(1,results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1,members.me(a).version());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=?",a.tenantId()));
    }

    @Test void frozenMemberCanReadAndWithdrawButCannotEditOrGrant() {
        Actor a=actor(); long id=Long.parseLong(members.register(a,"会员")); publishDocument(a,"v1");
        members.consent(a,"grant",0,"MARKETING","v1","GRANT");
        members.changeStatus(a,"freeze",id,1,"FROZEN","人工核查");
        assertEquals("FROZEN",members.me(a).status());
        assertCode("MEMBER_FROZEN",()->members.updateProfile(a,"edit",2,"不能修改"));
        assertCode("MEMBER_FROZEN",()->members.consent(a,"grant2",2,"MARKETING","v1","GRANT"));
        members.consent(a,"withdraw",2,"MARKETING","v1","WITHDRAW");
        assertEquals("WITHDRAW",members.consents(a,0,20).getLast().action());
        members.changeStatus(a,"activate",id,3,"ACTIVE","核查完成");
        members.updateProfile(a,"edit2",4,"恢复后可修改");
        assertEquals(5,members.me(a).version());
    }

    @Test void freezePreservesHistoricalConsumptionAndCrossTenantIsRejected() {
        Fixture f=orderFixture(3); Actor a=f.actor(); long id=Long.parseLong(members.me(a).memberId());
        var order=factOrder(f,"history"); confirmFact(f,order);
        members.changeStatus(a,"freeze",id,0,"FROZEN","核查");
        assertTrue(members.me(a).everPaid());
        assertEquals(Long.parseLong(order.orderId()),count("SELECT first_paid_order_id FROM tc_member_trade_fact WHERE tenant_id=?",a.tenantId()));
        Actor other=actor();
        assertCode("NOT_FOUND",()->members.changeStatus(other,"attack",id,1,"ACTIVE","越权"));
        assertTrue(members.list(other.tenantId(),0,20).isEmpty());
        assertCode("INVALID_ARGUMENT",()->members.changeStatus(a,"invalid",id,1,"DELETED","错误目标"));
    }

    @Test void consentHistoryIsAppendOnlyAndRetriesCannotUndoWithdrawal() {
        Actor a=actor(); members.register(a,"会员"); publishDocument(a,"v1");
        String grant=members.consent(a,"g",0,"MARKETING","v1","GRANT");
        members.consent(a,"w",1,"MARKETING","v1","WITHDRAW");
        assertEquals(grant,members.consent(a,"g",0,"MARKETING","v1","GRANT"));
        assertEquals(2,members.me(a).version());
        var events=members.consents(a,0,20);
        assertEquals(List.of("GRANT","WITHDRAW"),events.stream().map(Consent::action).toList());
        assertEquals(List.of(1L,2L),events.stream().map(Consent::memberVersion).toList());
        assertEquals(1,members.consents(a,Long.parseLong(events.getFirst().id()),20).size());
        assertCode("STATE_CONFLICT",()->members.consent(a,"w2",2,"MARKETING","v1","WITHDRAW"));
    }

    @Test void consentRequiresPublishedVersionAndWithdrawalMatchesLatestGrant() {
        Actor a=actor(); members.register(a,"会员");
        assertCode("NOT_FOUND",()->members.consent(a,"unpublished",0,"MARKETING","v1","GRANT"));
        publishDocument(a,"v1"); publishDocument(a,"v2");
        members.consent(a,"g1",0,"MARKETING","v1","GRANT");
        members.consent(a,"g2",1,"MARKETING","v2","GRANT");
        assertCode("STATE_CONFLICT",()->members.consent(a,"old-withdraw",2,"MARKETING","v1","WITHDRAW"));
        assertCode("STATE_CONFLICT",()->members.consent(a,"duplicate-grant",2,"MARKETING","v2","GRANT"));
        members.consent(a,"new-withdraw",2,"MARKETING","v2","WITHDRAW");
        assertEquals(3,members.consents(a,0,20).size());
    }

    @Test void documentPublicationIsImmutableAndTenantScoped() {
        Actor a=actor(); String id=publishDocument(a,"v1");
        assertEquals(id,publishDocument(a,"v1"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->documents.publish(a,"different-key","MARKETING","v1","新内容","https://terms.test/v1","b".repeat(64)));
        assertEquals(1,documents.list(a.tenantId(),0,20).size());
        assertTrue(documents.list(a.tenantId()+10000,0,20).isEmpty());
        assertCode("INVALID_ARGUMENT",()->documents.publish(a,"bad","MARKETING","v3","条款","http://terms.test/v3","a".repeat(64)));
        assertCode("INVALID_ARGUMENT",()->documents.publish(a,"bad2","MARKETING","v3","条款","https://terms.test/v3","fake"));
    }

    @Test void memberChangeAndAuditRollbackWithOuterTransaction() {
        Actor a=actor(); members.register(a,"原名称");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->{
            members.updateProfile(a,"profile",0,"新名称");
            throw new IllegalStateException("模拟上层业务失败");
        }));
        assertEquals("原名称",members.me(a).displayName()); assertEquals(0,members.me(a).version());
        assertEquals(0,count("SELECT COUNT(*) FROM tc_operation_audit WHERE tenant_id=?",a.tenantId()));
        assertEquals(1,count("SELECT COUNT(*) FROM tc_outbox WHERE tenant_id=?",a.tenantId()));
        members.updateProfile(a,"profile",0,"重试成功");
        assertEquals("重试成功",members.me(a).displayName());
    }

    @Test void readSideDetailsAndLedgerPaginationDoNotLeakTenants() {
        Fixture f=fixture(10);
        inventory.adjust(f.actor(),"second",f.warehouse(),f.sku(),5,"再次到货");
        assertEquals(Long.toString(f.warehouse()),inventory.warehouses(f.actor().tenantId(),0,20).getFirst().id());
        var first=inventory.ledgers(f.actor().tenantId(),f.warehouse(),f.sku(),0,1).getFirst();
        var second=inventory.ledgers(f.actor().tenantId(),f.warehouse(),f.sku(),Long.parseLong(first.id()),1).getFirst();
        assertEquals(10,first.availableDelta()); assertEquals(5,second.availableDelta());
        assertTrue(inventory.ledgers(f.actor().tenantId()+10000,f.warehouse(),f.sku(),0,20).isEmpty());
        long product=Long.parseLong(catalog.list(f.actor().tenantId(),0,20,true).getFirst().productId());
        assertEquals("PUBLISHED",catalog.detail(f.actor().tenantId(),product,false).status());
        assertCode("NOT_FOUND",()->catalog.detail(f.actor().tenantId()+10000,product,true));
        catalog.unpublish(f.actor(),"off",product,1);
        assertCode("NOT_FOUND",()->catalog.detail(f.actor().tenantId(),product,false));
        assertEquals("OFFLINE",catalog.detail(f.actor().tenantId(),product,true).status());
    }

    @Test void httpMemberLifecycleAndNewAdminReadsAreSecured() throws Exception {
        long tenant=TENANTS.incrementAndGet(); jwt("lifecycle-member","",tenant); jwt("lifecycle-admin","trade.admin",tenant);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        String id=json.readTree(request("POST","/api/v1/trade/members:register","lifecycle-member","{\"displayName\":\"会员\"}").body()).path("resourceId").asText();
        assertEquals(200,request("PATCH","/api/v1/trade/members/me","lifecycle-member","{\"expectedVersion\":0,\"displayName\":\"新名称\"}","profile").statusCode());
        assertEquals(403,request("GET","/api/v1/trade/admin/members","lifecycle-member",null).statusCode());
        String freeze="{\"expectedVersion\":1,\"targetStatus\":\"FROZEN\",\"reason\":\"测试冻结\"}";
        assertEquals(403,request("POST","/api/v1/trade/admin/members/"+id+":change-status","lifecycle-member",freeze,"freeze-denied").statusCode());
        assertEquals(200,request("POST","/api/v1/trade/admin/members/"+id+":change-status","lifecycle-admin",freeze,"freeze").statusCode());
        assertEquals("FROZEN",json.readTree(request("GET","/api/v1/trade/members/me","lifecycle-member",null).body()).path("status").asText());
        assertEquals(400,request("POST","/api/v1/trade/members/me/consents","lifecycle-member","{\"purpose\":\"MARKETING\",\"documentVersion\":\"v1\",\"action\":\"GRANT\"}","missing-version").statusCode());
        assertEquals(200,request("GET","/api/v1/trade/admin/warehouses","lifecycle-admin",null).statusCode());
        assertEquals(403,request("GET","/api/v1/trade/admin/inventory-ledgers?warehouseId=1&skuId=1","lifecycle-member",null).statusCode());
        assertEquals(400,request("GET","/api/v1/trade/admin/inventory-ledgers","lifecycle-admin",null).statusCode());
    }

    private String publishDocument(Actor a,String version) {
        return documents.publish(a,"document-"+version,"MARKETING",version,"营销条款","https://terms.test/"+version,"a".repeat(64));
    }
    /** 完整HTTP查询验证真实MySQL投影、游标及管理员与会员权限的分离。 */
    @Test void adminQueryOrdersQuotesAndTenantBoundaries() throws Exception {
        Fixture f=paymentFixture(5);var one=factOrder(f,"admin-one");var two=factOrder(f,"admin-two");
        assertEquals(2,application.getBean(com.lrj.trade.order.AdminOrderQueryService.class).page(f.actor().tenantId(),new com.lrj.trade.core.query.AdminQuery(0,20,null,null,Map.of())).items().size());
        jwt("query-admin","trade.admin",f.actor().tenantId());jwt("query-other","trade.admin",f.actor().tenantId()+100000);
        jwt("query-member","",f.actor().tenantId());var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var page=request("GET","/api/v1/trade/admin/orders?limit=1","query-admin",null);assertEquals(200,page.statusCode(),page.body());
        var root=json.readTree(page.body());assertTrue(root.path("hasMore").asBoolean());assertEquals(one.orderNo(),root.path("items").get(0).path("orderNo").asText());
        assertFalse(root.path("items").get(0).has("cursor"));assertTrue(root.path("items").get(0).path("orderId").isString());
        var next=request("GET","/api/v1/trade/admin/orders?limit=1&after="+root.path("nextAfter").asText(),"query-admin",null);
        assertEquals(two.orderNo(),json.readTree(next.body()).path("items").get(0).path("orderNo").asText());assertFalse(json.readTree(next.body()).path("hasMore").asBoolean());
        var detail=request("GET","/api/v1/trade/admin/orders/"+one.orderNo(),"query-admin",null);assertEquals(200,detail.statusCode(),detail.body());
        var orderJson=json.readTree(detail.body());assertEquals(one.orderNo(),orderJson.path("orderNo").asText());assertFalse(orderJson.has("summary"));assertTrue(orderJson.path("items").get(0).path("specification").isObject());
        assertTrue(one.orderNo().matches("TRD-ORD-[0-9]{6}-[0-9]{10}"));
        assertEquals(one.orderNo(),orderJson.path("businessNo").asString());
        String[] segments=one.orderNo().split("-");
        String tailQuery=segments[2]+"-"+segments[3].substring(6);
        var tailPage=request("GET","/api/v1/trade/admin/orders?orderNo="+tailQuery,"query-admin",null);
        assertEquals(200,tailPage.statusCode(),tailPage.body());
        assertTrue(json.readTree(tailPage.body()).path("items").valueStream()
            .anyMatch(item->one.orderNo().equals(item.path("orderNo").asString())));
        assertEquals(0,json.readTree(request("GET","/api/v1/trade/admin/orders?orderNo="+tailQuery,
            "query-other",null).body()).path("items").size());
        assertEquals(400,request("GET","/api/v1/trade/admin/orders?orderNo=261332-0001","query-admin",null).statusCode());
        assertFalse(detail.body().contains("reservationKey"));assertFalse(detail.body().contains("address"));
        assertEquals("no-store",detail.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(403,request("GET","/api/v1/trade/admin/orders","query-member",null).statusCode());
        assertEquals(404,request("GET","/api/v1/trade/admin/orders/"+one.orderNo(),"query-other",null).statusCode());
        assertEquals(0,json.readTree(request("GET","/api/v1/trade/admin/orders?orderNo="+one.orderNo(),"query-other",null).body()).path("items").size());
        assertNotEquals(200,request("GET","/api/v1/trade/orders/"+one.orderNo(),"query-admin",null).statusCode());
        var quote=request("GET","/api/v1/trade/admin/quotes/"+one.quoteId(),"query-admin",null);assertEquals(200,quote.statusCode(),quote.body());assertEquals(1,json.readTree(quote.body()).path("items").size());
        assertEquals(200,request("GET","/api/v1/trade/admin/quotes?quoteId="+one.quoteId(),"query-admin",null).statusCode());
        assertEquals(404,request("GET","/api/v1/trade/admin/quotes/"+one.quoteId(),"query-other",null).statusCode());
        for(String query:List.of("?limit=101","?limit=0","?after=01","?memberId=9223372036854775808","?status=SUCCESS","?tenantId=1","?limit=1&limit=2","?createdFrom=2026-09-01T00:00:00Z"))
            assertEquals(400,request("GET","/api/v1/trade/admin/orders"+query,"query-admin",null).statusCode(),query);
    }
    /** 实际收款与异常资金分别展示，尝试子集合不能越过父支付租户。 */
    @Test void adminQueryPaymentsReceiptsAndAmounts() throws Exception {
        Fixture f=paymentFixture(4);var order=factOrder(f,"admin-payment");var pay=createPayment(f.actor(),"pay",order.orderNo(),"TEST_A","return");
        var normal=receive(f,pay,"admin-normal",100,"CNY");var excess=receive(f,pay,"admin-excess",100,"CNY");
        assertTrue(pay.paymentNo().matches("TRD-PAY-[0-9]{6}-[0-9]{10}"));
        assertEquals(pay.paymentNo(),pay.businessNo());
        jwt("query-admin","trade.admin",f.actor().tenantId());jwt("query-other","trade.admin",f.actor().tenantId()+100000);var json=tools.jackson.databind.json.JsonMapper.builder().build();
        for(String path:List.of("/payments?orderNo="+order.orderNo(),"/payments/"+pay.paymentNo(),"/payments/"+pay.paymentNo()+"/attempts","/payment-receipts?orderNo="+order.orderNo(),"/payment-receipts/"+normal.receiptNo())) {
            var response=request("GET","/api/v1/trade/admin"+path,"query-admin",null);assertEquals(200,response.statusCode(),response.body());
            assertFalse(response.body().contains("vault:"));assertFalse(response.body().contains("providerTransactionNo"));assertFalse(response.body().contains("evidenceRef"));
        }
        var detail=json.readTree(request("GET","/api/v1/trade/admin/payments/"+pay.paymentNo(),"query-admin",null).body());assertEquals(normal.receiptNo(),detail.path("succeededReceiptNo").asText());
        var abnormal=json.readTree(request("GET","/api/v1/trade/admin/payment-receipts?abnormalOnly=true","query-admin",null).body());assertEquals(1,abnormal.path("items").size());assertEquals(excess.receiptNo(),abnormal.path("items").get(0).path("receiptNo").asText());
        assertEquals(404,request("GET","/api/v1/trade/admin/payments/"+pay.paymentNo()+"/attempts","query-other",null).statusCode());
        assertEquals(404,request("GET","/api/v1/trade/admin/payment-receipts/"+normal.receiptNo(),"query-other",null).statusCode());
        assertEquals(400,request("GET","/api/v1/trade/admin/payment-receipts?abnormalOnly=true&disposition=NORMAL","query-admin",null).statusCode());
        var response=request("GET","/api/v1/trade/admin/payment-receipts/"+normal.receiptNo(),"query-admin",null);assertEquals(100,json.readTree(response.body()).path("unreservedAmount").asLong());
    }
    /** 退款队列兼容旧数组，详情原因来自持久任务，退款不伪装成实际退货。 */
    @Test void adminQueryRefundsAndReturnInspection() throws Exception {
        Fixture f=paymentFixture(2);var order=paidRefundOrder(f,"admin-refund",2);var refund=requestRefund(f,order,"refund",50);
        assertTrue(refund.businessNo().matches("TRD-RFD-[0-9]{6}-[0-9]{10}"));
        assertTrue(refund.refundNo().matches("RF-[a-f0-9-]{36}"));
        jwt("query-admin","trade.admin",f.actor().tenantId());jwt("query-other","trade.admin",f.actor().tenantId()+100000);var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var queue=request("GET","/api/v1/trade/admin/refunds","query-admin",null);assertEquals(200,queue.statusCode(),queue.body());assertTrue(json.readTree(queue.body()).isArray());assertEquals(refund.refundNo(),json.readTree(queue.body()).get(0).path("refundNo").asText());
        assertEquals(1,json.readTree(request("GET","/api/v1/trade/admin/refunds?status=ALL&orderNo="+order.orderNo(),"query-admin",null).body()).size());
        jdbc.update("UPDATE tc_job_lease SET last_error='CHANNEL_UNAVAILABLE' WHERE tenant_id=? AND job_type='REFUND_RECOVER' AND business_key=?",f.actor().tenantId(),refund.refundNo());
        var detail=request("GET","/api/v1/trade/admin/refunds/"+refund.refundNo(),"query-admin",null);assertEquals(200,detail.statusCode(),detail.body());
        var byBusinessNo=request("GET","/api/v1/trade/admin/refunds/"+refund.businessNo(),"query-admin",null);
        assertEquals(200,byBusinessNo.statusCode(),byBusinessNo.body());
        assertEquals(refund.refundNo(),json.readTree(byBusinessNo.body()).path("refundNo").asText());
        var refundJson=json.readTree(detail.body());assertEquals("CHANNEL_UNAVAILABLE",refundJson.path("processingReasonCode").asText());assertFalse(refundJson.has("reason"));assertEquals(1,refundJson.path("items").size());
        assertEquals(404,request("GET","/api/v1/trade/admin/refunds/"+refund.refundNo(),"query-other",null).statusCode());
        var ingress=application.getBean(com.lrj.trade.order.FulfillmentIngressService.class);ingress.deliver(f.actor(),"deliver",order.orderNo(),"source","proof");
        var rma=ingress.createReturn(f.actor(),"rma",Long.parseLong(order.items().getFirst().itemId()),f.warehouse(),2);
        var open=request("GET","/api/v1/trade/admin/returns?status=OPEN&orderNo="+order.orderNo(),"query-admin",null);assertEquals(1,json.readTree(open.body()).path("items").size());
        var before=request("GET","/api/v1/trade/admin/returns/"+rma.resourceNo(),"query-admin",null);assertEquals("\"0\"",before.headers().firstValue("ETag").orElseThrow());assertTrue(json.readTree(before.body()).path("inspection").isNull());
        ingress.inspect(f.actor(),"inspect",rma.resourceNo(),0,"PARTIAL",1,"safe-proof");
        var after=request("GET","/api/v1/trade/admin/returns/"+rma.resourceNo(),"query-admin",null);assertEquals(200,after.statusCode(),after.body());assertEquals("\"1\"",after.headers().firstValue("ETag").orElseThrow());
        var inspection=json.readTree(after.body()).path("inspection");assertEquals(2,inspection.path("receivedQuantity").asLong());assertEquals(1,inspection.path("restockQuantity").asLong());assertTrue(inspection.has("recordedAt"));
        assertEquals(404,request("GET","/api/v1/trade/admin/returns/"+rma.resourceNo(),"query-other",null).statusCode());
        assertEquals(400,request("GET","/api/v1/trade/admin/returns?status=PARTIAL","query-admin",null).statusCode());
    }
    /** 证据与租约要求双scope，管理员入口不改变内部可信服务允许列表。 */
    @Test void adminQueryEvidenceEligibilityAndJobs() throws Exception {
        Fixture f=paymentFixture(2);var order=paidRefundOrder(f,"admin-evidence",1);long member=Long.parseLong(members.me(f.actor()).memberId());
        jwt("query-admin","trade.admin",f.actor().tenantId());jwt("query-diag","trade.admin trade.admin.evidence.read trade.admin.jobs.read",f.actor().tenantId());jwt("query-other","trade.admin trade.admin.evidence.read trade.admin.jobs.read",f.actor().tenantId()+100000);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();String ev="/api/v1/trade/admin/orders/"+order.orderNo()+"/referral-evidence";
        assertEquals(403,request("HEAD",ev,"query-admin",null).statusCode());assertEquals(403,request("GET",ev,"query-admin",null).statusCode());var evidence=request("GET",ev,"query-diag",null);assertEquals(200,evidence.statusCode(),evidence.body());assertEquals(100,json.readTree(evidence.body()).path("paidAmount").asLong());
        assertEquals(404,request("GET",ev,"query-other",null).statusCode());
        String body="{\"policyVersion\":\"NEVER_CONFIRMED_PAID_V1\"}";String path="/api/v1/trade/admin/members/"+member+"/eligibility:query";
        assertEquals(403,request("POST",path,"query-admin",body).statusCode());var eligible=request("POST",path,"query-diag",body);assertEquals(200,eligible.statusCode(),eligible.body());assertFalse(json.readTree(eligible.body()).path("evidence").path("eligible").asBoolean());
        assertEquals(404,request("POST",path,"query-other",body).statusCode());
        assertEquals(400,request("POST",path,"query-diag","{\"policyVersion\":\"NEVER_CONFIRMED_PAID_V1\",\"memberId\":\"1\"}").statusCode());
        assertEquals(403,request("HEAD","/api/v1/trade/admin/jobs","query-admin",null).statusCode());
        assertEquals(403,request("GET","/api/v1/trade/admin/jobs","query-admin",null).statusCode());
        var jobs=request("GET","/api/v1/trade/admin/jobs?jobType=ORDER_RESOLVE","query-diag",null);assertEquals(200,jobs.statusCode(),jobs.body());var job=json.readTree(jobs.body()).path("items").get(0);assertFalse(job.has("fencingToken"));assertFalse(job.has("owner"));assertFalse(job.has("businessKey"));
        assertEquals(200,request("GET","/api/v1/trade/admin/jobs/"+job.path("jobId").asText(),"query-diag",null).statusCode());
        assertEquals(404,request("GET","/api/v1/trade/admin/jobs/"+job.path("jobId").asText(),"query-other",null).statusCode());
        assertEquals(403,request("GET","/internal/v1/orders/"+order.orderId()+"/referral-evidence","query-diag",null).statusCode());
        assertEquals(403,request("POST","/api/v1/trade/integrations/refund-approvals","query-admin","{\"signedReceipt\":\"fake\"}").statusCode());
    }

    /** 隔离数据验证选择性索引和实际Mapper执行计划，不能用LIMIT代替索引证明。 */
    @Test void adminQueryIndexedPlansAndLeaseObservation() throws Exception {
        long tenant=TENANTS.incrementAndGet();Instant at=Instant.now();
        var batch=new ArrayList<Object[]>();
        for(int i=0;i<2000;i++) batch.add(new Object[]{tenant,"plan-"+i,i==1999?"REVIEW":"DONE"});
        jdbc.batchUpdate("INSERT INTO tc_job_lease(tenant_id,job_type,business_key,status,next_run_at,created_at,updated_at) VALUES(?,'ORDER_RESOLVE',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",batch);
        var query=new com.lrj.trade.core.query.AdminQuery(0,20,null,null,Map.of("status","REVIEW"));
        var service=application.getBean(com.lrj.trade.jobs.AdminJobQueryService.class);var page=service.page(tenant,query);assertEquals(1,page.items().size());assertFalse(page.items().getFirst().leaseExpired());
        var config=application.getBean(org.apache.ibatis.session.SqlSessionFactory.class).getConfiguration();
        var statement=config.getMappedStatement("com.lrj.trade.jobs.mapper.AdminJobMapper.page");
        Map<String,Object> params=Map.of("tenant",tenant,"q",query,"at",at);var bound=statement.getBoundSql(params);
        try(var connection=application.getBean(javax.sql.DataSource.class).getConnection();var sql=connection.prepareStatement("EXPLAIN "+bound.getSql())) {
            new org.apache.ibatis.scripting.defaults.DefaultParameterHandler(statement,params,bound).setParameters(sql);
            try(var result=sql.executeQuery()) { assertTrue(result.next());assertNotEquals("ALL",result.getString("type"));assertEquals("ix_admin_job_status",result.getString("key")); }
        }
        String job=page.items().getFirst().jobId();
        jdbc.update("UPDATE tc_job_lease SET status='RUNNING',owner='private-worker',fencing_token=1,attempts=1,lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND,next_run_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE tenant_id=? AND id=?",tenant,Long.parseLong(job));
        var expired=service.page(tenant,new com.lrj.trade.core.query.AdminQuery(0,20,null,null,Map.of("leaseExpired",true)));
        assertEquals(1,expired.items().size());assertTrue(expired.items().getFirst().leaseExpired());assertEquals("RUNNING",expired.items().getFirst().status());
        assertNull(service.detail(tenant,job).businessReference());
        assertEquals(1,count("SELECT COUNT(*) FROM tc_job_lease WHERE tenant_id=? AND status='RUNNING'",tenant));
        var indexes=jdbc.queryForList("""
            SELECT DISTINCT index_name FROM information_schema.statistics
            WHERE table_schema=DATABASE() AND (index_name LIKE 'ix_admin_%' OR index_name='ix_operations_refund_confirmed')
            """,String.class);
        assertEquals(Set.of(
            "ix_admin_order_status","ix_admin_order_financial","ix_admin_order_created",
            "ix_admin_payment_status","ix_admin_payment_member","ix_admin_payment_created","ix_admin_attempt_page",
            "ix_admin_receipt_disposition","ix_admin_receipt_order","ix_admin_receipt_created","ix_admin_receipt_confirmed",
            "ix_admin_return_page","ix_admin_return_status","ix_admin_return_order","ix_admin_return_created",
            "ix_admin_quote_created","ix_admin_refund_page","ix_admin_refund_created","ix_admin_refund_approval_deadline",
            "ix_admin_job_page","ix_admin_job_status","ix_admin_job_created","ix_admin_product_name",
            "ix_admin_member_state","ix_admin_member_registration","ix_admin_inventory_sku","ix_admin_inventory_available",
            "ix_operations_refund_confirmed"),new HashSet<>(indexes));
    }

    private void jwt(String token,String scope,long tenant) {
        when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg","RS256").issuer("https://identity.test")
            .subject(token).claim("tenant_id",Long.toString(tenant)).claim("scope",scope).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build());
    }
    private HttpResponse<String> request(String method,String path,String token,String body) throws Exception {
        return request(method,path,token,body,null);
    }
    private HttpResponse<String> request(String method,String path,String token,String body,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(path.startsWith("/api/v1/trade/admin/") && body!=null) {
            var expected=tools.jackson.databind.json.JsonMapper.builder().build().readTree(body).get("expectedVersion");
            if(expected!=null && expected.isIntegralNumber()) builder.header("If-Match","\""+expected.asText()+"\"");
        }
        if(key!=null) builder.header("Idempotency-Key",key);
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(body!=null) builder.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private Actor actor() { return new Actor(TENANTS.incrementAndGet(),"https://identity.test","tester"); }

    // 前序支付测试的明确前置条件：测试侧注入可信ALLOW事实，不修改生产默认WAIT策略。
    private com.lrj.trade.payment.model.PaymentView createPayment(Actor actor,String key,String order,String channel,String returnId) {
        var payment=payments.create(actor,key,order,channel,returnId);
        if(payment.activeAttemptNo()!=null) approveRisk(actor.tenantId(),payment.activeAttemptNo());
        return payment;
    }
    private void approveRisk(long tenant,String requestNo) {
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            var evaluation=paymentRisks.prepareInTransaction(tenant,requestNo);
            if(evaluation.state().equals("PENDING")) paymentRisks.acceptInTransaction(evaluation,
                new com.lrj.trade.payment.PaymentRiskPort.Decision(evaluation.evaluationNo(),tenant,requestNo,evaluation.inputHash(),
                    com.lrj.trade.payment.PaymentRiskPort.Action.ALLOW,"test-"+evaluation.evaluationNo(),"test-rule-v1","NONE",
                    clock.instant().plusSeconds(60)));
        });
    }
    private Fixture fixture(long quantity) {
        Actor a=actor();
        String p=catalog.create(a,"p","p","商品","s",100,"CNY");
        catalog.publish(a,"pub",Long.parseLong(p),0);
        long sku=Long.parseLong(catalog.list(a.tenantId(),0,20,true).getFirst().skuId());
        long wh=Long.parseLong(inventory.warehouse(a,"w","w","测试仓"));
        inventory.adjust(a,"initial",wh,sku,quantity,"初始化");
        return new Fixture(a,wh,sku);
    }
    private Balance balance(Fixture f) { return inventory.balances(f.actor().tenantId(),0,20).getFirst(); }
    private long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    private void assertConservation(Fixture f) {
        var b=balance(f);
        assertEquals(b.stockTotal(),b.available()+b.reserved()+b.sold());
        assertEquals(b.available(),count("SELECT SUM(available_delta) FROM tc_inventory_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(b.reserved(),count("SELECT SUM(reserved_delta) FROM tc_inventory_ledger WHERE tenant_id=?",f.actor().tenantId()));
        assertEquals(b.sold(),count("SELECT SUM(sold_delta) FROM tc_inventory_ledger WHERE tenant_id=?",f.actor().tenantId()));
    }
    private void assertCode(String code,Runnable action) { assertEquals(code,assertThrows(TradeException.class,action::run).code()); }
    private boolean terminal(Runnable action) {
        try { action.run(); return true; } catch(TradeException conflict) { assertEquals("STATE_CONFLICT",conflict.code()); return false; }
    }
    private <T> List<T> parallel(int count,Callable<T> work) throws Exception {
        try(var pool=Executors.newFixedThreadPool(12)) {
            var tasks=new ArrayList<Future<T>>();
            for(int n=0;n<count;n++) tasks.add(pool.submit(work));
            var results=new ArrayList<T>();
            for(var task:tasks) results.add(task.get(30,TimeUnit.SECONDS));
            return results;
        }
    }
    /** 测试租户和库存引用，不共享生产资源。 */
    private record Fixture(Actor actor,long warehouse,long sku) {}
}
