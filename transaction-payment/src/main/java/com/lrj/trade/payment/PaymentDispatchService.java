package com.lrj.trade.payment;

import com.lrj.trade.core.*;
import com.lrj.trade.order.OrderPaymentPort;
import com.lrj.trade.payment.mapper.*;
import com.lrj.trade.payment.model.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 生成受风险门禁保护的内部执行计划，不执行网络；真实供应商及限流仍须认证接入。 */
@Service
public class PaymentDispatchService implements PaymentRecoveryPort {
    private final PaymentMapper mapper;
    private final ChannelMapper channels;
    private final OrderPaymentPort orders;
    private final Clock clock;
    private final PaymentRiskPort risks;
    private final TransactionTemplate tx;
    /** 网络调用方必须等待本地事务提交，不能在回调中持锁发请求。 */
    public PaymentDispatchService(PaymentMapper mapper,ChannelMapper channels,OrderPaymentPort orders,Clock clock,PlatformTransactionManager transactions,PaymentRiskPort risks) {
        this.mapper=mapper; this.channels=channels; this.orders=orders; this.clock=clock;
        this.risks=risks;
        this.tx=new TransactionTemplate(transactions); this.tx.setTimeout(10);
    }
    /** 首次有效ALLOW才许可CREATE；风险待定返回RISK，已派发未知按原号QUERY，关闭中只许可CLOSE。 */
    @Transactional(propagation=Propagation.NEVER)
    public PaymentDispatch prepare(long tenant,String requestNo) {
        Checks.positive(tenant); Checks.text(requestNo,64);
        // 拒绝外层事务后再建立自有短事务，返回CREATE时本地许可一定已经提交。
        return tx.execute(status->{
            var plan=prepareInTransaction(tenant,requestNo);
            assertCreateAllowedInTransaction(tenant,plan);
            return plan;
        });
    }
    /** 为恢复工作者加入已有事务；调用者在同事务末尾执行租约fencing后才可发网络。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public PaymentDispatch prepareInTransaction(long tenant,String requestNo) {
        return preparePlan(tenant,requestNo,false);
    }

    /** 同号补建也重新获取订单锁与关闭屏障；原号和冻结参数不得修改。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public PaymentDispatch prepareReplayInTransaction(long tenant,String requestNo) {
        return preparePlan(tenant,requestNo,true);
    }

    /** 仅重新验证已有CREATE许可，不输出第二份补建计划；失败使本事务的提交标记一起回滚。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void assertCreateAllowedInTransaction(long tenant,PaymentDispatch plan) {
        if("CREATE".equals(plan.action()) && !"CREATE".equals(preparePlan(tenant,plan.requestNo(),true).action()))
            throw new TradeException("PAYMENT_PERMISSION_CHANGED","支付创建许可在提交前已失效");
    }

    private PaymentDispatch preparePlan(long tenant,String requestNo,boolean safeReplay) {
        Checks.positive(tenant); Checks.text(requestNo,64);
        var reference=mapper.findAttempt(tenant,requestNo).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","支付尝试不存在"));
        var order=orders.lockPaymentOrder(tenant,Long.parseLong(reference.orderId()));
        var intent=mapper.lockByOrder(tenant,Long.parseLong(reference.orderId())).getFirst();
        var attempt=mapper.lockAttempt(tenant,requestNo).getFirst();
        var channel=channels.findById(tenant,Long.parseLong(attempt.channelId())).getFirst();
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String action;
        if(attempt.status().equals("CLOSED") && intent.activeAttemptNo()==null && intent.status().equals("READY")
            && (order.status().equals("CLOSING") || !now.isBefore(order.expiresAt()))) {
            // 使用已持久化的可靠终态，而不是要求渠道重复证明早已关闭的请求。
            if(mapper.finishWithoutActive(tenant,Long.parseLong(intent.intentId()),now)!=1)
                throw new TradeException("STATE_CONFLICT","支付意图已改变");
            orders.beginPaymentClose(tenant,Long.parseLong(order.orderId()));
            orders.completePaymentClose(tenant,Long.parseLong(order.orderId()));
            action="DONE";
        } else if(attempt.status().equals("CLOSED") || intent.status().equals("SUCCEEDED") || order.status().equals("CANCELLED")) action="DONE";
        else if(attempt.status().equals("SUCCEEDED")) action="REVIEW";
        else if(order.status().equals("CLOSING") || intent.closeFenceAt()!=null || !now.isBefore(order.expiresAt())) {
            orders.beginPaymentClose(tenant,Long.parseLong(order.orderId()));
            mapper.fenceIntent(tenant,Long.parseLong(intent.intentId()),now);
            action="CLOSE";
        } else if(attempt.submittedAt()==null || safeReplay) {
            // 首次创建与同号补建共用风险门禁；等待风险不等于已经获得渠道创建许可。
            var risk=risks.prepareInTransaction(tenant,requestNo);
            var permittedAt=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            if(!permittedAt.isBefore(order.expiresAt())) {
                // 风险持久查询也消耗时间；不能用进入门禁前的旧时间跨过订单到期点创建。
                orders.beginPaymentClose(tenant,Long.parseLong(order.orderId()));
                mapper.fenceIntent(tenant,Long.parseLong(intent.intentId()),permittedAt);
                action="CLOSE";
            } else if(risk.state().equals("DENY")) action="REVIEW";
            else if(!risk.state().equals("ALLOW") || risk.validUntil()==null || !permittedAt.isBefore(risk.validUntil())) action="RISK";
            else {
                if(attempt.submittedAt()==null) {
                    if(mapper.markSubmitted(tenant,requestNo,permittedAt)!=1) throw new TradeException("STATE_CONFLICT","支付尝试已改变");
                    attempt=mapper.lockAttempt(tenant,requestNo).getFirst();
                }
                action="CREATE";
            }
        } else action="QUERY";
        return new PaymentDispatch(action,requestNo,channel.channelCode(),channel.channelMerchantNo(),channel.credentialRef(),
            intent.expectedAmount(),intent.currency(),channel.returnUrl(),attempt.version());
    }
}
