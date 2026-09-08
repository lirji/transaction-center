package com.lrj.trade.server;
import com.lrj.trade.core.*;
import com.lrj.trade.payment.*;
import com.lrj.trade.refund.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** 固定两个只读策略，不能把任意action传递给允许CREATE的渠道适配层。 */
@Service
public class ReconciliationQueryExecutor implements ReconciliationQueryExecutorPort {
    private final PaymentQueryPlanPort paymentPlans;private final RefundQueryPlanPort refundPlans;
    private final PaymentChannelPort payments;private final RefundChannelPort refunds;
    /** 复用耐久证据Inbox交接，但隔离普通恢复器的补建和状态推进。 */
    public ReconciliationQueryExecutor(PaymentQueryPlanPort paymentPlans,RefundQueryPlanPort refundPlans,PaymentChannelPort payments,RefundChannelPort refunds) {this.paymentPlans=paymentPlans;this.refundPlans=refundPlans;this.payments=payments;this.refunds=refunds;}
    /** 验证仅产生原绑定QUERY计划，不执行网络。 */
    @Override public void validate(long tenant,long channel,String action,String type,String reference) {
        if("QUERY_PAYMENT".equals(action)) paymentPlan(tenant,channel,type,reference);
        else if("QUERY_REFUND".equals(action)) refundPlan(tenant,channel,type,reference);
        else throw new TradeException("INVALID_ARGUMENT","不支持的对账查询动作");
    }
    /** 失租也不丢弃已认证证据；租约结果更新由Worker独立fencing负责。 */
    @Override @Transactional(propagation=Propagation.NEVER)
    public Outcome execute(long tenant,long channel,String action,String type,String reference) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("对账查询不得持事务执行");
        if("QUERY_PAYMENT".equals(action)) return Outcome.valueOf(payments.invoke(tenant,paymentPlan(tenant,channel,type,reference)).name());
        if("QUERY_REFUND".equals(action)) return Outcome.valueOf(refunds.invoke(tenant,refundPlan(tenant,channel,type,reference)).name());
        throw new TradeException("INVALID_ARGUMENT","不支持的对账查询动作");
    }
    private com.lrj.trade.payment.model.PaymentDispatch paymentPlan(long tenant,long channel,String type,String reference) {
        var plan=paymentPlans.query(tenant,channel,type,reference);
        if(plan==null || !"QUERY".equals(plan.action())) throw new TradeException("RECONCILIATION_QUERY_UNAVAILABLE","只查单入口拒绝非QUERY计划");
        return plan;
    }
    private RefundExecutionPort.Plan refundPlan(long tenant,long channel,String type,String reference) {
        if(!"REFUND".equals(type)) throw new TradeException("INVALID_ARGUMENT","退款查单须原退款引用");
        var plan=refundPlans.query(tenant,channel,reference);
        if(plan==null || !"QUERY".equals(plan.action())) throw new TradeException("RECONCILIATION_QUERY_UNAVAILABLE","只查单入口拒绝非QUERY计划");
        return plan;
    }
}
