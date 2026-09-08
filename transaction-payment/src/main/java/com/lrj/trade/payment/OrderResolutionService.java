package com.lrj.trade.payment;

import com.lrj.trade.core.*;
import com.lrj.trade.order.OrderPaymentPort;
import com.lrj.trade.payment.mapper.PaymentMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 先锁订单再检查支付可靠终态；库存释放与最终订单任务fencing共享本地事务。 */
@Service
public class OrderResolutionService implements OrderResolutionPort {
    private final OrderPaymentPort orders;
    private final PaymentMapper payments;
    private final PaymentRecoveryPort recovery;
    private final Clock clock;
    private final Outbox outbox;

    /** 支付只读取自身Mapper，订单及库存变化经公开领域端口执行。 */
    public OrderResolutionService(OrderPaymentPort orders,PaymentMapper payments,PaymentRecoveryPort recovery,Clock clock,Outbox outbox) {
        this.orders=orders; this.payments=payments; this.recovery=recovery; this.clock=clock; this.outbox=outbox;
    }

    /** 到期只产生关闭检查；只有无渠道许可或已有可靠关闭事实才实际释放。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Plan prepare(long tenant,String orderNo) {
        var order=orders.lockPaymentOrderByNumber(tenant,orderNo);
        long orderId=Long.parseLong(order.orderId());
        if(Set.of("PAID","FULFILLING","COMPLETED","CANCELLED").contains(order.status())) return result(State.DONE,0);
        if(!Set.of("PENDING_PAYMENT","CLOSING").contains(order.status()) || !"UNPAID".equals(order.financialStatus()))
            return result(State.REVIEW,0);
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if("PENDING_PAYMENT".equals(order.status()) && now.isBefore(order.expiresAt()))
            return result(State.WAIT,Math.max(100,Math.min(86400000,Duration.between(now,order.expiresAt()).toMillis())));
        var intents=payments.lockByOrder(tenant,orderId);
        if(intents.isEmpty()) {
            // 支付许可与意图原本同事务；不把缺失意图的矛盾状态当成可靠安全证明。
            if(order.paymentStartedAt()!=null) return result(State.REVIEW,0);
            orders.beginPaymentClose(tenant,orderId);
            orders.completePaymentClose(tenant,orderId);
            return result(State.DONE,0);
        }
        var intent=intents.getFirst();
        if(intent.activeAttemptNo()!=null) {
            var attempt=payments.lockAttempt(tenant,intent.activeAttemptNo()).getFirst();
            if("PREPARED".equals(attempt.status()) && attempt.submittedAt()==null) {
                // 所有网络许可都会先同事务写submitted_at；持订单/意图/尝试锁时为空证明从未发出许可。
                // 这是本地派发证明，不伪装渠道回调；持久关闭后旧工作者无法再取得CREATE许可。
                String evidence="LOCAL_UNSUBMITTED_V1:"+intent.activeAttemptNo();
                if(payments.closeAttempt(tenant,intent.activeAttemptNo(),evidence,now)!=1
                    || payments.clearActive(tenant,Long.parseLong(intent.intentId()),intent.activeAttemptNo(),"CLOSED",now,now)!=1)
                    throw new TradeException("STATE_CONFLICT","未派发尝试关闭冲突");
                orders.beginPaymentClose(tenant,orderId);
                orders.completePaymentClose(tenant,orderId);
                outbox.append(tenant,"payment.attempt-closed.internal.v0",intent.activeAttemptNo());
                return result(State.DONE,0);
            }
            var plan=recovery.prepareInTransaction(tenant,intent.activeAttemptNo());
            if("REVIEW".equals(plan.action())) return result(State.REVIEW,0);
            if("DONE".equals(plan.action())) {
                var current=orders.lockPaymentOrder(tenant,orderId);
                return result(Set.of("PAID","CANCELLED").contains(current.status())?State.DONE:State.REVIEW,0);
            }
            // 订单已关闭中或已到期，因此不得从此端口发CREATE/QUERY重开支付。
            if(!"CLOSE".equals(plan.action())) throw new TradeException("STATE_CONFLICT","关闭解析生成了非关闭调用");
            return new Plan(State.NETWORK,0,plan);
        }
        if("READY".equals(intent.status())) {
            if(payments.finishWithoutActive(tenant,Long.parseLong(intent.intentId()),now)!=1)
                throw new TradeException("STATE_CONFLICT","支付意图已改变");
        } else if(!"CLOSED".equals(intent.status())) return result(State.REVIEW,0);
        // READY无活动尝试仅来自可靠关闭后的清理；CLOSED已持久保存不可支付终态。
        orders.beginPaymentClose(tenant,orderId);
        orders.completePaymentClose(tenant,orderId);
        return result(State.DONE,0);
    }

    private Plan result(State state,long delay) { return new Plan(state,delay,null); }
}
