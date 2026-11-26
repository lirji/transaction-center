package com.lrj.trade.payment;

import com.lrj.trade.core.*;
import com.lrj.trade.member.MemberTradingPort;
import com.lrj.trade.order.OrderPaymentPort;
import com.lrj.trade.order.MerchantTradingPort;
import com.lrj.trade.payment.mapper.*;
import com.lrj.trade.payment.model.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 支付HTTP只持久受理，不在订单事务内等待风控或渠道网络。 */
@Service
public class PaymentService {
    private final PaymentMapper mapper;
    private final ChannelMapper channels;
    private final OrderPaymentPort orders;
    private final MerchantTradingPort merchants;
    private final MemberTradingPort members;
    private final Commands commands;
    private final AdmissionService admission;
    private final Outbox outbox;
    private final Clock clock;
    private final JobSchedulerPort jobs;
    /** 所有跨领域访问经过Port，支付Mapper不读取其他领域表。 */
    public PaymentService(PaymentMapper mapper,ChannelMapper channels,OrderPaymentPort orders,MerchantTradingPort merchants,
        MemberTradingPort members,Commands commands,Outbox outbox,Clock clock,JobSchedulerPort jobs,AdmissionService admission) {
        this.mapper=mapper; this.channels=channels; this.orders=orders; this.merchants=merchants; this.members=members;
        this.commands=commands; this.outbox=outbox; this.clock=clock; this.jobs=jobs; this.admission=admission;
    }
    /** 冻结金额来自订单；不同HTTP键也不能绕过唯一意图和活跃尝试。 */
    public PaymentView create(Actor actor,String key,String orderNo,String channelCode,String returnId) {
        Checks.text(orderNo,64); Checks.text(channelCode,64); Checks.text(returnId,64);
        String paymentNo=commands.run(actor,"payment.create",key,Checks.hash(orderNo,channelCode,returnId),()->{
            var policy=admission.acquire(actor.tenantId());
            var order=orders.authorizePayment(actor,orderNo);
            var merchant=merchants.requireActive(actor.tenantId());
            if(!merchant.merchantNo().equals(order.merchantNo())) throw new TradeException("STATE_CONFLICT","订单商户配置不一致");
            var channel=channels.lockActive(actor.tenantId(),order.merchantNo(),channelCode,order.currency()).stream().findFirst()
                .orElseThrow(()->new TradeException("CHANNEL_UNAVAILABLE","支付渠道未配置或不可用"));
            if(!channel.returnUrlId().equals(returnId)) throw new TradeException("INVALID_ARGUMENT","回跳地址别名未登记");
            var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            if(!now.isBefore(order.expiresAt())) throw new TradeException("ORDER_EXPIRED","订单已过支付期限");
            long orderId=Long.parseLong(order.orderId());
            var found=mapper.lockByOrder(actor.tenantId(),orderId);
            if(found.isEmpty()) {
                policy.requirePayment();
                for (int attempt=0; attempt<BusinessNumber.MAX_ISSUE_ATTEMPTS; attempt++) {
                    String number=BusinessNumber.next(BusinessNumber.Type.PAYMENT,now);
                    try {
                        mapper.insertIntent(actor.tenantId(),number,orderId,Long.parseLong(order.memberId()),
                            order.payableAmount(),order.currency(),now);
                        break;
                    } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                        // 订单唯一或其它冲突继续交原边界处理，只有公开编号可重新发号。
                        if (attempt==BusinessNumber.MAX_ISSUE_ATTEMPTS-1 || mapper.lockBusinessNumber(number)==null) {
                            throw duplicate;
                        }
                    }
                }
                found=mapper.lockByOrder(actor.tenantId(),orderId);
            }
            var intent=found.getFirst();
            if(intent.closeFenceAt()!=null || intent.succeededReceiptId()!=null) throw new TradeException("STATE_CONFLICT","支付意图不允许新尝试");
            if(intent.activeAttemptNo()!=null) {
                var attempt=mapper.lockAttempt(actor.tenantId(),intent.activeAttemptNo()).getFirst();
                if(!attempt.channelId().equals(channel.channelId()))
                    throw new TradeException("PAYMENT_UNRESOLVED","旧渠道未可靠关闭，不能切换渠道");
                return intent.paymentNo();
            }
            policy.requirePayment();
            String requestNo="REQ-"+UUID.randomUUID();
            mapper.insertAttempt(actor.tenantId(),Long.parseLong(intent.intentId()),orderId,Long.parseLong(channel.channelId()),requestNo,now);
            if(mapper.setActive(actor.tenantId(),Long.parseLong(intent.intentId()),requestNo,now)!=1)
                throw new TradeException("STATE_CONFLICT","支付意图已有未决尝试");
            outbox.append(actor.tenantId(),"payment.prepared.internal.v0",requestNo);
            // 与渠道尝试同事务，提交后进程崩溃也不能丢失恢复工作。
            jobs.enqueue(actor.tenantId(),JobType.PAYMENT_RECOVER,requestNo);
            return intent.paymentNo();
        });
        return read(actor,paymentNo);
    }
    /** 本人只读本地状态，页面回跳不会改变支付结论。 */
    public PaymentView read(Actor actor,String number) {
        Checks.text(number,64);
        return mapper.findOwn(actor.tenantId(),members.memberId(actor),number).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","支付意图不存在"));
    }
}
