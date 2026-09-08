package com.lrj.trade.server;

import com.lrj.trade.core.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证拒绝审计的资源上限和故障隔离，数据库独立持久性另由集成测试证明。 */
class RefundApprovalRejectionAuditTest {
    private final Actor actor=new Actor(1,"https://identity.test","workflow");
    private PlatformTransactionManager transactions() {
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->{
            TransactionDefinition definition=call.getArgument(0);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getPropagationBehavior());
            assertEquals(3,definition.getTimeout());
            return new SimpleTransactionStatus();
        });
        return manager;
    }
    @Test void commitsOnlyFixedCategoryAndDigestEvenWhenMetricsFail() {
        var writer=mock(AuditWriter.class); var manager=transactions(); var metrics=mock(MeterRegistry.class);
        when(metrics.counter(anyString(),any(String[].class))).thenThrow(new IllegalStateException("metrics unavailable"));
        var audit=new RefundApprovalRejectionAudit(writer,manager,metrics);
        String id=audit.record(actor,"secret.jwt.payload","arbitrary-secret-category");
        assertTrue(id.startsWith("ARJ-"));
        verify(writer).append(actor,"refund.approval.rejected-request","APPROVAL_REQUEST",id,
            "OTHER_REJECTION:"+Checks.hash("secret.jwt.payload"),0,0);
        verify(manager).commit(any()); verify(manager,never()).rollback(any());
        assertEquals(403,new Errors().approvalRejected(new ApprovalRejection("REFUND_APPROVAL_UNTRUSTED","拒绝",id)).getStatusCode().value());
        assertEquals(id,new Errors().approvalRejected(new ApprovalRejection("REFUND_APPROVAL_UNTRUSTED","拒绝",id)).getBody().traceId());
    }
    @Test void auditWrapperPreservesBusinessHttpClassification() {
        var errors=new Errors();
        for(String code:java.util.List.of("STATE_CONFLICT","VERSION_CONFLICT","IDEMPOTENCY_CONFLICT","NOT_FOUND",
            "INVALID_ARGUMENT","REFUND_APPROVAL_UNTRUSTED","REFUND_APPROVAL_EVENT_CONFLICT","CALLBACK_ENCRYPTION_UNAVAILABLE","REFUND_APPROVAL_INVALID"))
            assertEquals(errors.business(new TradeException(code,"拒绝")).getStatusCode(),
                errors.approvalRejected(new ApprovalRejection(code,"拒绝","ARJ-test")).getStatusCode(),code);
        assertEquals(409,errors.approvalRejected(new ApprovalRejection("STATE_CONFLICT","拒绝","ARJ-test")).getStatusCode().value());
    }
    @Test void rollbackAndCommitFailureRemainUnavailableAndReleaseSlots() {
        var writer=mock(AuditWriter.class); var manager=transactions(); var metrics=mock(MeterRegistry.class);
        when(metrics.counter(anyString(),any(String[].class))).thenThrow(new IllegalStateException("metrics unavailable"));
        var audit=new RefundApprovalRejectionAudit(writer,manager,metrics);
        doThrow(new IllegalStateException("database unavailable")).when(writer).append(any(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        for(int i=0;i<8;i++) assertEquals("REFUND_APPROVAL_AUDIT_UNAVAILABLE",
            assertThrows(TradeException.class,()->audit.record(actor,"signed","REFUND_APPROVAL_UNTRUSTED")).code());
        verify(manager,times(8)).rollback(any());
        reset(writer); doThrow(new IllegalStateException("commit unavailable")).when(manager).commit(any());
        assertEquals("REFUND_APPROVAL_AUDIT_UNAVAILABLE",assertThrows(TradeException.class,()->audit.record(actor,"signed","NOT_FOUND")).code());
        doNothing().when(manager).commit(any());
        assertTrue(audit.record(actor,"signed","NOT_FOUND").startsWith("ARJ-"));
    }
    @Test void fourSlotsRejectImmediatelyAndRecoverAfterConcurrentCompletion() throws Exception {
        var writer=mock(AuditWriter.class); var manager=transactions(); var metrics=mock(MeterRegistry.class);
        when(metrics.counter(anyString(),any(String[].class))).thenThrow(new IllegalStateException("metrics unavailable"));
        var audit=new RefundApprovalRejectionAudit(writer,manager,metrics);
        var entered=new CountDownLatch(4); var release=new CountDownLatch(1);
        doAnswer(call->{ entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); return null; })
            .when(writer).append(any(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        var pool=Executors.newFixedThreadPool(4);
        var futures=new java.util.ArrayList<Future<String>>();
        try {
            for(int i=0;i<4;i++) futures.add(pool.submit(()->audit.record(actor,"signed","NOT_FOUND")));
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertEquals("REFUND_APPROVAL_AUDIT_UNAVAILABLE",
                assertThrows(TradeException.class,()->audit.record(actor,"signed","NOT_FOUND")).code()));
            verify(manager,times(4)).getTransaction(any());
            release.countDown();
            for(var future:futures) assertTrue(future.get(10,TimeUnit.SECONDS).startsWith("ARJ-"));
            assertTrue(audit.record(actor,"signed","NOT_FOUND").startsWith("ARJ-"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void oversizePayloadIsNotHashedOrStoredAndMetricsHaveBoundedLabels() {
        var writer=mock(AuditWriter.class); var metrics=new SimpleMeterRegistry();
        var audit=new RefundApprovalRejectionAudit(writer,transactions(),metrics);
        String id=audit.record(actor,"s".repeat(16385),"NOT_FOUND");
        verify(writer).append(actor,"refund.approval.rejected-request","APPROVAL_REQUEST",id,"NOT_FOUND:"+Checks.hash("INVALID_SIZE"),0,0);
        assertEquals(1,metrics.get("trade.refund.approval.rejection.audit").tag("outcome","recorded").counter().count());
    }
}
