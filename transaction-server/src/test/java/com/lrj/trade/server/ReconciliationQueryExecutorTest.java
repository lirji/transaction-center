package com.lrj.trade.server;
import com.lrj.trade.core.*;
import com.lrj.trade.payment.*;
import com.lrj.trade.payment.model.PaymentDispatch;
import com.lrj.trade.refund.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 只查单适配层对错误计划和查无结果均不进入创建、关闭或同号补建。 */
class ReconciliationQueryExecutorTest {
    @Test void queryAbsenceReturnsObservationWithoutFallbackAndRejectsOtherActions() {
        var plans=mock(PaymentQueryPlanPort.class);var refundPlans=mock(RefundQueryPlanPort.class);
        var payments=mock(PaymentChannelPort.class);var refunds=mock(RefundChannelPort.class);
        var service=new ReconciliationQueryExecutor(plans,refundPlans,payments,refunds);
        var query=new PaymentDispatch("QUERY","original","TEST_A","merchant","vault:test",100,"CNY","https://return.test",1);
        when(plans.query(1,2,"PAYMENT_ATTEMPT","original")).thenReturn(query);
        when(payments.invoke(1,query)).thenReturn(PaymentChannelPort.Outcome.REPLAYABLE_ABSENCE);
        assertEquals(ReconciliationQueryExecutorPort.Outcome.REPLAYABLE_ABSENCE,service.execute(1,2,"QUERY_PAYMENT","PAYMENT_ATTEMPT","original"));
        verify(payments).invoke(1,query);verifyNoInteractions(refunds,refundPlans);
        assertThrows(TradeException.class,()->service.execute(1,2,"CREATE","PAYMENT_ATTEMPT","original"));
        when(plans.query(1,2,"PAYMENT_ATTEMPT","original")).thenReturn(new PaymentDispatch("CREATE","original","TEST_A","merchant","vault:test",100,"CNY","https://return.test",1));
        assertThrows(TradeException.class,()->service.execute(1,2,"QUERY_PAYMENT","PAYMENT_ATTEMPT","original"));verifyNoMoreInteractions(payments);
    }
    @Test void activeTransactionCannotInvokeChannelEvenWithoutSpringProxy() {
        var plans=mock(PaymentQueryPlanPort.class);var rp=mock(RefundQueryPlanPort.class);var payment=mock(PaymentChannelPort.class);var refund=mock(RefundChannelPort.class);
        var service=new ReconciliationQueryExecutor(plans,rp,payment,refund);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(IllegalStateException.class,()->service.execute(1,2,"QUERY_PAYMENT","PAYMENT_ATTEMPT","request"));}
        finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
        verifyNoInteractions(plans,rp,payment,refund);
    }
}
