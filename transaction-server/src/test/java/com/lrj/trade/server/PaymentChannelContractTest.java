package com.lrj.trade.server;

import com.lrj.trade.core.TradeException;
import com.lrj.trade.payment.*;
import com.lrj.trade.payment.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 渠道策略协议测试，不宣称真实供应商验签或幂等能力已认证。 */
class PaymentChannelContractTest {
    private PaymentDispatch plan(String action) {
        return new PaymentDispatch(action,"request","TEST","merchant","secret-ref",100,"CNY","https://return.test",1);
    }
    private PaymentChannelAdapter adapter(PaymentChannelAdapter.Reply reply) {
        var adapter=mock(PaymentChannelAdapter.class);
        when(adapter.channelCode()).thenReturn("TEST");
        when(adapter.execute(any(),any())).thenReturn(reply);
        return adapter;
    }
    @Test void duplicateStrategiesAndInconsistentRepliesAreRejected() {
        var adapter=adapter(new PaymentChannelAdapter.Reply(PaymentChannelAdapter.State.UNKNOWN,null));
        assertThrows(IllegalArgumentException.class,()->new PaymentChannelService(List.of(adapter,adapter),mock(PaymentCallbackService.class)));
        assertThrows(IllegalArgumentException.class,()->new PaymentChannelAdapter.Reply(PaymentChannelAdapter.State.EVIDENCE,null));
        assertThrows(IllegalArgumentException.class,()->new PaymentChannelAdapter.Reply(null,null));
    }
    @Test void ordinaryAbsenceCannotEnableReplayWithoutCertifiedCapability() {
        var adapter=adapter(new PaymentChannelAdapter.Reply(PaymentChannelAdapter.State.REPLAYABLE_ABSENCE,null));
        var inbox=mock(PaymentCallbackService.class); var service=new PaymentChannelService(List.of(adapter),inbox);
        assertEquals("CHANNEL_PROTOCOL_INVALID",assertThrows(TradeException.class,()->service.invoke(1,plan("QUERY"))).code());
        when(adapter.supportsSafeCreateReplay()).thenReturn(true);
        assertEquals(PaymentChannelPort.Outcome.REPLAYABLE_ABSENCE,service.invoke(1,plan("QUERY")));
        assertEquals("CHANNEL_PROTOCOL_INVALID",assertThrows(TradeException.class,()->service.invoke(1,plan("CLOSE"))).code());
        verifyNoInteractions(inbox);
    }
    @Test void foreignRequestEvidenceNeverReachesDurableInbox() {
        var evidence=new VerifiedPaymentCallback("TEST","merchant","event","foreign","RECEIVED","txn",100L,"CNY",Instant.EPOCH,"cert",new byte[]{1});
        var adapter=adapter(new PaymentChannelAdapter.Reply(PaymentChannelAdapter.State.EVIDENCE,evidence));
        var inbox=mock(PaymentCallbackService.class);
        assertEquals("CHANNEL_BINDING_CONFLICT",assertThrows(TradeException.class,
            ()->new PaymentChannelService(List.of(adapter),inbox).invoke(1,plan("QUERY"))).code());
        verifyNoInteractions(inbox);
    }
    @Test void abnormalMoneyIsPreservedAndInboxFailureCannotBeAcknowledged() {
        var evidence=new VerifiedPaymentCallback("TEST","merchant","event","request","RECEIVED","txn",99L,"USD",Instant.EPOCH,"cert",new byte[]{1});
        var adapter=adapter(new PaymentChannelAdapter.Reply(PaymentChannelAdapter.State.EVIDENCE,evidence));
        var inbox=mock(PaymentCallbackService.class); var service=new PaymentChannelService(List.of(adapter),inbox);
        doThrow(new TradeException("CALLBACK_ENCRYPTION_UNAVAILABLE","测试暂不可用")).when(inbox).accept(1,evidence);
        assertEquals("CALLBACK_ENCRYPTION_UNAVAILABLE",assertThrows(TradeException.class,()->service.invoke(1,plan("QUERY"))).code());
        doReturn("callback").when(inbox).accept(1,evidence);
        assertEquals(PaymentChannelPort.Outcome.EVIDENCE_ACCEPTED,service.invoke(1,plan("QUERY")));
        verify(inbox,times(2)).accept(1,evidence);
    }
    @Test void uncertainTransportIsNotAFalsePaymentFailure() {
        var adapter=adapter(null);
        when(adapter.execute(any(),any())).thenThrow(new ChannelCallUncertainException());
        var inbox=mock(PaymentCallbackService.class);
        assertEquals(PaymentChannelPort.Outcome.UNKNOWN,new PaymentChannelService(List.of(adapter),inbox).invoke(1,plan("CREATE")));
        verifyNoInteractions(inbox);
        assertEquals("渠道调用结果未知",new ChannelCallUncertainException().getMessage());
    }
}
