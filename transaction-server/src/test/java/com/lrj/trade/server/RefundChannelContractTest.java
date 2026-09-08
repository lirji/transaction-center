package com.lrj.trade.server;

import com.lrj.trade.core.TradeException;
import com.lrj.trade.refund.*;
import com.lrj.trade.refund.model.VerifiedRefundEvidence;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 渠道策略边界测试；不替代真实供应商认证或耐久Inbox集成验收。 */
class RefundChannelContractTest {
    @Test void bindingMismatchOrMissingCredentialsPreventsNetwork() {
        var adapter=adapter(null); var inbox=mock(RefundEvidencePort.class);
        for(var binding:List.of(
            new com.lrj.trade.payment.PaymentChannelBindingPort.Binding(2,9,"4","TEST","merchant","CNY","vault/old",1),
            new com.lrj.trade.payment.PaymentChannelBindingPort.Binding(1,8,"4","TEST","merchant","CNY","vault/old",1),
            new com.lrj.trade.payment.PaymentChannelBindingPort.Binding(1,9,"4","OTHER","merchant","CNY","vault/old",1),
            new com.lrj.trade.payment.PaymentChannelBindingPort.Binding(1,9,"4","TEST","new-owner","CNY","vault/old",1),
            new com.lrj.trade.payment.PaymentChannelBindingPort.Binding(1,9,"4","TEST","merchant","USD","vault/old",1),
            new com.lrj.trade.payment.PaymentChannelBindingPort.Binding(1,9,"4","TEST","merchant","CNY",null,1))) {
            var service=new RefundChannelService(List.of(adapter),List.of(inbox),(t,a)->binding);
            assertEquals("REFUND_CHANNEL_BINDING_CONFLICT",assertThrows(TradeException.class,()->service.invoke(1,plan("CREATE"))).code());
        }
        var missing=new RefundChannelService(List.of(adapter),List.of(inbox),(t,a)->{
            throw new TradeException("PAYMENT_CHANNEL_BINDING_UNAVAILABLE","缺失");
        });
        assertThrows(TradeException.class,()->missing.invoke(1,plan("QUERY")));
        verify(adapter,never()).execute(any(),any(),any()); verifyNoInteractions(inbox);
    }
    @Test void originalCredentialBindingReachesAdapterUnchanged() {
        var adapter=adapter(new RefundChannelAdapter.Reply(RefundChannelAdapter.State.PENDING,null));
        var binding=bindings().resolve(1,9);
        var service=new RefundChannelService(List.of(adapter),List.of(mock(RefundEvidencePort.class)),(t,a)->binding);
        var plan=plan("CREATE"); service.invoke(1,plan);
        verify(adapter).execute(same(plan),same(binding),eq(Duration.ofSeconds(10)));
    }
    private String boundMerchant="merchant";
    private com.lrj.trade.payment.PaymentChannelBindingPort bindings() {
        return (t,a)->new com.lrj.trade.payment.PaymentChannelBindingPort.Binding(t,a,"4","TEST",boundMerchant,"CNY","vault/original",1);
    }
    private RefundExecutionPort.Plan plan(String action) {
        return new RefundExecutionPort.Plan(1,"refund","request","receipt","9",
            "TEST","merchant","original",100,"CNY",action);
    }
    private VerifiedRefundEvidence evidence(String request,long amount,String currency,String original) {
        return new VerifiedRefundEvidence("TEST","merchant","event",request,original,"provider",
            VerifiedRefundEvidence.Kind.SUCCEEDED,amount,currency,Instant.EPOCH,"cert",new byte[]{1});
    }
    private RefundChannelAdapter adapter(RefundChannelAdapter.Reply reply) {
        var adapter=mock(RefundChannelAdapter.class);
        when(adapter.channelCode()).thenReturn("TEST");
        when(adapter.execute(any(),any(),any())).thenReturn(reply);
        return adapter;
    }
    private RefundChannelService service(RefundChannelAdapter adapter,RefundEvidencePort inbox) {
        return new RefundChannelService(List.of(adapter),List.of(inbox),bindings());
    }

    @Test void duplicateStrategiesReceiversAndInvalidRepliesFailClosed() {
        var adapter=adapter(new RefundChannelAdapter.Reply(RefundChannelAdapter.State.UNKNOWN,null));
        var inbox=mock(RefundEvidencePort.class);
        assertThrows(IllegalArgumentException.class,()->new RefundChannelService(List.of(adapter,adapter),List.of(inbox),bindings()));
        assertThrows(IllegalArgumentException.class,()->new RefundChannelService(List.of(adapter),List.of(inbox,inbox),bindings()));
        assertThrows(IllegalArgumentException.class,()->new RefundChannelAdapter.Reply(null,null));
        assertThrows(IllegalArgumentException.class,()->new RefundChannelAdapter.Reply(RefundChannelAdapter.State.EVIDENCE,null));
        assertThrows(IllegalArgumentException.class,()->new RefundChannelAdapter.Reply(
            RefundChannelAdapter.State.PENDING,evidence("request",100,"CNY","original")));
    }
    @Test void unavailableDurableReceiverPreventsRemoteSideEffect() {
        var adapter=adapter(null); var inbox=mock(RefundEvidencePort.class);
        assertEquals(RefundChannelPort.Outcome.UNAVAILABLE,
            new RefundChannelService(List.of(adapter),List.of(),bindings()).invoke(1,plan("CREATE")));
        verify(adapter,never()).execute(any(),any(),any());
        assertEquals(RefundChannelPort.Outcome.UNAVAILABLE,
            new RefundChannelService(List.of(),List.of(inbox),bindings()).invoke(1,plan("QUERY")));
        verifyNoInteractions(inbox);
    }
    @Test void invalidTenantActionsAndAmountsNeverReachNetwork() {
        var adapter=adapter(null); var inbox=mock(RefundEvidencePort.class); var service=service(adapter,inbox);
        assertThrows(TradeException.class,()->service.invoke(2,plan("CREATE")));
        for(String action:Arrays.asList(null,"DONE","WAIT_APPROVAL","CLOSE","")) {
            assertThrows(TradeException.class,()->service.invoke(1,plan(action)));
        }
        assertThrows(TradeException.class,()->service.invoke(1,null));
        for(long amount:new long[]{0,-1,9007199254740992L}) {
            var invalid=new RefundExecutionPort.Plan(1,"refund","request","receipt","9",
                "TEST","merchant","original",amount,"CNY","CREATE");
            assertThrows(TradeException.class,()->service.invoke(1,invalid));
        }
        verify(adapter,never()).execute(any(),any(),any()); verifyNoInteractions(inbox);
    }
    @Test void activeTransactionIsRejectedEvenWithoutSpringProxy() {
        var adapter=adapter(null); var inbox=mock(RefundEvidencePort.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class,()->service(adapter,inbox).invoke(1,plan("CREATE")));
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verify(adapter,never()).execute(any(),any(),any()); verifyNoInteractions(inbox);
    }
    @Test void existingPaymentMerchantLengthRemainsCompatible() {
        boundMerchant="m".repeat(255);
        var adapter=adapter(new RefundChannelAdapter.Reply(RefundChannelAdapter.State.PENDING,null));
        var inbox=mock(RefundEvidencePort.class); var service=service(adapter,inbox);
        var valid=new RefundExecutionPort.Plan(1,"refund","request","receipt","9",
            "TEST","m".repeat(255),"original",100,"CNY","QUERY");
        assertEquals(RefundChannelPort.Outcome.PENDING,service.invoke(1,valid));
        verify(adapter).execute(same(valid),any(),eq(Duration.ofSeconds(10)));
    }
    @Test void uncertainTransportAndPendingNeverProduceFundsEvidence() {
        var adapter=adapter(null); var inbox=mock(RefundEvidencePort.class); var service=service(adapter,inbox);
        when(adapter.execute(any(),any(),any())).thenThrow(new RefundChannelAdapter.Uncertain());
        assertEquals(RefundChannelPort.Outcome.UNKNOWN,service.invoke(1,plan("CREATE")));
        doReturn(new RefundChannelAdapter.Reply(RefundChannelAdapter.State.UNKNOWN,null)).when(adapter).execute(any(),any(),any());
        assertEquals(RefundChannelPort.Outcome.UNKNOWN,service.invoke(1,plan("QUERY")));
        doReturn(new RefundChannelAdapter.Reply(RefundChannelAdapter.State.PENDING,null)).when(adapter).execute(any(),any(),any());
        assertEquals(RefundChannelPort.Outcome.PENDING,service.invoke(1,plan("QUERY")));
        verifyNoInteractions(inbox);
    }
    @Test void mismatchedRoutingCannotContaminateAnotherRequestInbox() {
        var inbox=mock(RefundEvidencePort.class);
        for(var proof:List.of(evidence("foreign",100,"CNY","original"),
            new VerifiedRefundEvidence("OTHER","merchant","event","request","original","provider",
                VerifiedRefundEvidence.Kind.SUCCEEDED,100L,"CNY",Instant.EPOCH,"cert",new byte[]{1}),
            new VerifiedRefundEvidence("TEST","foreign","event","request","original","provider",
                VerifiedRefundEvidence.Kind.SUCCEEDED,100L,"CNY",Instant.EPOCH,"cert",new byte[]{1}))) {
            var adapter=adapter(new RefundChannelAdapter.Reply(RefundChannelAdapter.State.EVIDENCE,proof));
            assertEquals("REFUND_CHANNEL_BINDING_CONFLICT",assertThrows(TradeException.class,
                ()->service(adapter,inbox).invoke(1,plan("QUERY"))).code());
        }
        verifyNoInteractions(inbox);
    }
    @Test void abnormalEvidenceIsPreservedAndReceiverFailureCannotBeAcknowledged() {
        var proof=evidence("request",99,"USD","wrong-original");
        var adapter=adapter(new RefundChannelAdapter.Reply(RefundChannelAdapter.State.EVIDENCE,proof));
        var inbox=mock(RefundEvidencePort.class); var service=service(adapter,inbox);
        doThrow(new TradeException("INBOX_UNAVAILABLE","测试持久化失败")).when(inbox).accept(1,proof);
        assertEquals("INBOX_UNAVAILABLE",assertThrows(TradeException.class,()->service.invoke(1,plan("QUERY"))).code());
        doNothing().when(inbox).accept(1,proof);
        assertEquals(RefundChannelPort.Outcome.EVIDENCE_ACCEPTED,service.invoke(1,plan("QUERY")));
        verify(inbox,times(2)).accept(1,proof);
    }
    @Test void fixedRequestAndTimeoutArePassedUnchangedOnRepeatedQuery() {
        var adapter=adapter(new RefundChannelAdapter.Reply(RefundChannelAdapter.State.PENDING,null));
        var inbox=mock(RefundEvidencePort.class); var service=service(adapter,inbox); var plan=plan("QUERY");
        service.invoke(1,plan); service.invoke(1,plan);
        verify(adapter,times(2)).execute(same(plan),any(),eq(Duration.ofSeconds(10)));
        verifyNoInteractions(inbox);
    }
    @Test void reliableFailureIsOnlyAcceptedAsDurableEvidence() {
        var proof=new VerifiedRefundEvidence("TEST","merchant","event","request","original",null,
            VerifiedRefundEvidence.Kind.FAILED_FINAL,null,null,Instant.EPOCH,"cert",new byte[]{1});
        var adapter=adapter(new RefundChannelAdapter.Reply(RefundChannelAdapter.State.EVIDENCE,proof));
        var inbox=mock(RefundEvidencePort.class);
        assertEquals(RefundChannelPort.Outcome.EVIDENCE_ACCEPTED,service(adapter,inbox).invoke(1,plan("QUERY")));
        verify(inbox).accept(1,proof);
    }
    @Test void nullReplyIsAProtocolErrorAndPayloadIsImmutableAndRedacted() {
        var adapter=adapter(null); var inbox=mock(RefundEvidencePort.class);
        assertEquals("REFUND_CHANNEL_PROTOCOL_INVALID",assertThrows(TradeException.class,
            ()->service(adapter,inbox).invoke(1,plan("QUERY"))).code());
        byte[] payload={1};
        var proof=new VerifiedRefundEvidence("TEST","merchant","event","request","original","provider",
            VerifiedRefundEvidence.Kind.SUCCEEDED,100L,"CNY",Instant.EPOCH,"cert",payload);
        payload[0]=2; var copy=proof.payload(); copy[0]=3;
        assertArrayEquals(new byte[]{1},proof.payload());
        assertEquals("VerifiedRefundEvidence[REDACTED]",proof.toString());
        assertFalse(new RefundChannelAdapter.Reply(RefundChannelAdapter.State.EVIDENCE,proof).toString().contains("merchant"));
        assertEquals("退款渠道调用结果未知",new RefundChannelAdapter.Uncertain().getMessage());
        verifyNoInteractions(inbox);
    }
}
