package com.lrj.trade.server;

import com.lrj.trade.payment.*;
import com.lrj.trade.payment.mapper.*;
import com.lrj.trade.payment.model.RiskEvaluation;
import com.lrj.trade.order.OrderPaymentPort;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 供应商边界失败关闭和事务保护，不代替实际HTTP认证合同。 */
class PaymentRiskProviderContractTest {
    private final Instant now=Instant.parse("2026-09-07T08:00:00Z");
    private RiskEvaluation evaluation() {
        return new RiskEvaluation("risk",1,"2","request","3","4","TEST","merchant",100,"CNY","hash",
            "PENDING",null,null,null,null,now,now.plusSeconds(300));
    }
    private PaymentRiskService service(List<PaymentRiskProvider> providers) {
        return new PaymentRiskService(mock(PaymentRiskMapper.class),mock(PaymentMapper.class),mock(ChannelMapper.class),
            mock(OrderPaymentPort.class),Clock.fixed(now,ZoneOffset.UTC),providers);
    }
    @Test void absentProviderCannotAllow() {
        assertEquals(PaymentRiskPort.Action.WAIT,service(List.of()).evaluate(evaluation()).action());
    }
    @Test void providerFailureAndEmptyResultCannotAllow() {
        assertEquals(PaymentRiskPort.Action.WAIT,service(List.of((e,t)->{ throw new IllegalStateException("敏感供应商内容"); })).evaluate(evaluation()).action());
        assertEquals(PaymentRiskPort.Action.WAIT,service(List.of((e,t)->null)).evaluate(evaluation()).action());
    }
    @Test void networkIsRejectedInsideTransactionEvenWithoutSpringProxy() {
        var source=mock(PaymentRiskProvider.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(com.lrj.trade.core.TradeException.class,()->service(List.of(source)).evaluate(evaluation())); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(source);
    }
    @Test void duplicateProvidersRejectAmbiguousRoutingAndResultsAreRedacted() {
        var source=mock(PaymentRiskProvider.class);
        assertThrows(IllegalArgumentException.class,()->service(List.of(source,source)));
        assertEquals("RiskEvaluation[REDACTED]",evaluation().toString());
        assertEquals("RiskDecision[REDACTED]",service(List.of()).evaluate(evaluation()).toString());
    }
}
