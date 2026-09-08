package com.lrj.trade.server;

import com.lrj.trade.core.TradeException;
import com.lrj.trade.payment.PaymentChannelBindingService;
import com.lrj.trade.payment.mapper.*;
import com.lrj.trade.payment.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原渠道解析边界；真实Mapper映射和跨域派发需要额外集成验证。 */
class PaymentChannelBindingTest {
    private final PaymentMapper payments=mock(PaymentMapper.class);
    private final ChannelMapper channels=mock(ChannelMapper.class);
    private final PaymentChannelBindingService service=new PaymentChannelBindingService(payments,channels);
    private void attempt() {
        when(payments.findAttemptById(7,9)).thenReturn(List.of(new PaymentAttempt("9","2","3","4","original",
            "SUCCEEDED",null,null,null,1)));
    }
    @Test void frozenHistoricalBindingDoesNotSelectCurrentConfiguration() {
        attempt();
        when(channels.findById(7,4)).thenReturn(List.of(new ChannelConfiguration("4","merchant","TEST","original-owner",
            "CNY","vault/original/v1","return","https://example.invalid","FROZEN",2)));
        var result=service.resolve(7,9);
        assertEquals("4",result.channelId()); assertEquals("vault/original/v1",result.credentialRef());
        assertEquals("original-owner",result.channelMerchantNo()); assertEquals(2,result.configurationVersion());
        assertEquals(7,result.tenantId()); assertEquals(9,result.attemptId());
        assertEquals("PaymentChannelBinding[REDACTED]",result.toString());
        verify(channels).findById(7,4); verifyNoMoreInteractions(channels);
    }
    @Test void otherTenantAndInvalidIdsCannotReadBinding() {
        assertEquals("NOT_FOUND",assertThrows(TradeException.class,()->service.resolve(8,9)).code());
        assertThrows(TradeException.class,()->service.resolve(0,9));
        assertThrows(TradeException.class,()->service.resolve(7,0));
        verifyNoInteractions(channels);
    }
    @Test void missingHistoricalConfigurationFailsWithoutFallback() {
        attempt();
        assertEquals("PAYMENT_CHANNEL_BINDING_UNAVAILABLE",assertThrows(TradeException.class,()->service.resolve(7,9)).code());
        verify(channels).findById(7,4); verifyNoMoreInteractions(channels);
    }
    @Test void emptyCredentialReferenceFailsClosed() {
        attempt();
        when(channels.findById(7,4)).thenReturn(List.of(new ChannelConfiguration("4","merchant","TEST","owner",
            "CNY"," ","return","https://example.invalid","ACTIVE",1)));
        assertEquals("PAYMENT_CHANNEL_BINDING_UNAVAILABLE",assertThrows(TradeException.class,()->service.resolve(7,9)).code());
    }
}
