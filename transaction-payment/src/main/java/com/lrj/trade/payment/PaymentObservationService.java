package com.lrj.trade.payment;

import com.lrj.trade.core.*;
import com.lrj.trade.order.OrderPaymentPort;
import com.lrj.trade.payment.mapper.*;
import com.lrj.trade.payment.model.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 已验真收款与运营账本及订单副作用原子提交；无网络请求，也不从客户端接收可信声明。 */
@Service
public class PaymentObservationService implements PaymentObservationPort {
    private final PaymentMapper mapper;
    private final ChannelMapper channels;
    private final OrderPaymentPort orders;
    private final Outbox outbox;
    private final Clock clock;
    private final JobSchedulerPort jobs;
    /** 各领域共享本地事务；回调耐久接收层后续接入此处理器。 */
    public PaymentObservationService(PaymentMapper mapper,ChannelMapper channels,OrderPaymentPort orders,Outbox outbox,Clock clock,JobSchedulerPort jobs) {
        this.mapper=mapper; this.channels=channels; this.orders=orders; this.outbox=outbox; this.clock=clock; this.jobs=jobs;
    }
    /** 所有真实收款都入账；正常资金至多一次确认订单，异常资金保留待补偿而不复活订单。 */
    @Override
    @Transactional(timeout=10)
    public PaymentReceipt received(long tenant,String requestNo,String transaction,long amount,String currency,Instant providerPaid,String evidence) {
        Checks.positive(tenant); Checks.text(requestNo,64); Checks.text(transaction,255); Checks.text(evidence,255);
        if(amount<=0 || amount>9007199254740991L || currency==null || !currency.matches("[A-Z]{3}") || providerPaid==null)
            throw new TradeException("INVALID_ARGUMENT","真实收款字段无效");
        providerPaid=providerPaid.truncatedTo(ChronoUnit.MILLIS);
        var reference=reference(tenant,requestNo);
        var order=orders.lockPaymentOrder(tenant,Long.parseLong(reference.orderId()));
        var intent=mapper.lockByOrder(tenant,Long.parseLong(order.orderId())).getFirst();
        var attempt=mapper.lockAttempt(tenant,requestNo).getFirst();
        var channel=channels.findById(tenant,Long.parseLong(attempt.channelId())).getFirst();
        String disposition;
        if(attempt.status().equals("CLOSED")) disposition="AFTER_TERMINAL";
        else if(order.status().equals("CANCELLED")) disposition="AFTER_CLOSE";
        else if(intent.succeededReceiptId()!=null) disposition="EXCESS";
        else if(amount!=intent.expectedAmount() || !currency.equals(intent.currency())) disposition="AMOUNT_MISMATCH";
        else if(!Set.of("PENDING_PAYMENT","CLOSING").contains(order.status()) || !order.financialStatus().equals("UNPAID")) disposition="EXCESS";
        else disposition="NORMAL";
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String receiptNo="RC-"+UUID.randomUUID();
        // 不先锁不存在的交易号，避免两个不同订单互持间隙锁后插入死锁。
        // 唯一键命中时绝不覆盖资金字段，用本地业务编号识别重放而非依赖影响行数。
        PaymentReceipt receipt=null;
        for (int attemptNo=0; attemptNo<BusinessNumber.MAX_ISSUE_ATTEMPTS; attemptNo++) {
            String number=BusinessNumber.next(BusinessNumber.Type.PAYMENT_RECEIPT,now);
            mapper.insertReceipt(tenant,receiptNo,number,Long.parseLong(intent.intentId()),Long.parseLong(attempt.attemptId()),
                Long.parseLong(order.orderId()),channel.channelCode(),channel.channelMerchantNo(),transaction,
                amount,currency,providerPaid,now,disposition,evidence);
            receipt=mapper.lockReceipt(tenant,channel.channelCode(),channel.channelMerchantNo(),transaction).stream().findFirst().orElse(null);
            if(receipt!=null || mapper.lockReceiptBusinessNumber(number)==null) break;
        }
        if(receipt==null) throw new TradeException("PAYMENT_FACT_CONFLICT","收款唯一约束与渠道引用冲突");
        if(!receipt.receiptNo().equals(receiptNo)) {
            if(!receipt.attemptId().equals(attempt.attemptId()) || receipt.amount()!=amount || !receipt.currency().equals(currency) || !receipt.providerPaidAt().equals(providerPaid))
                throw new TradeException("PAYMENT_FACT_CONFLICT","渠道交易号对应的收款事实冲突");
            return receipt;
        }
        long receiptId=Long.parseLong(receipt.receiptId());
        MoneyLedgerNumber.write(mapper,now,number->mapper.insertLedger(tenant,receiptId,number,amount,currency,evidence,now));
        if(disposition.equals("NORMAL")) {
            var confirmation=orders.confirmPayment(tenant,Long.parseLong(order.orderId()),amount,currency);
            if(mapper.markSucceeded(tenant,Long.parseLong(intent.intentId()),Long.parseLong(receipt.receiptId()),now)!=1)
                throw new TradeException("STATE_CONFLICT","正常收款指针已改变");
            if(!confirmation.orderId().equals(receipt.orderId()) || !confirmation.memberId().equals(order.memberId()))
                throw new TradeException("STATE_CONFLICT","支付认定与真实收款归属不一致");
            // 使用等待会员首单锁后产生的确认时间，不能用此前采样的收款now冒充paidAt。
            outbox.orderPaid(OrderPaidEvent.create(tenant,new OrderPaidEvent.Data(confirmation.orderId(),confirmation.memberId(),
                receipt.receiptNo(),amount,currency,confirmation.paidAt(),confirmation.firstOrderPolicy(),
                confirmation.isFirstConfirmedPaidOrder(),confirmation.evidenceVersion())));
        } else {
            mapper.markReview(tenant,Long.parseLong(intent.intentId()),now);
            // 收款与唯一补偿任务同事务；后台只建立待审批申请，不能把入账当成已退款。
            jobs.enqueue(tenant,JobType.REFUND_COMPENSATE,receipt.receiptNo());
            outbox.append(tenant,"payment.receipt-exception.internal.v0",receipt.receiptId());
        }
        mapper.markAttemptSucceeded(tenant,requestNo,now);
        outbox.append(tenant,"payment.received.internal.v0",receipt.receiptId());
        return receipt;
    }
    /** 可靠关闭与收款竞争同一订单行；已成功资金不被关闭覆盖。 */
    @Override
    @Transactional(timeout=10)
    public void closed(long tenant,String requestNo,String evidence) {
        Checks.positive(tenant); Checks.text(requestNo,64); Checks.text(evidence,255);
        var reference=reference(tenant,requestNo);
        var order=orders.lockPaymentOrder(tenant,Long.parseLong(reference.orderId()));
        var intent=mapper.lockByOrder(tenant,Long.parseLong(order.orderId())).getFirst();
        var attempt=mapper.lockAttempt(tenant,requestNo).getFirst();
        if(attempt.status().equals("SUCCEEDED")) throw new TradeException("STATE_CONFLICT","已收款尝试不能回退关闭");
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(!attempt.status().equals("CLOSED") && mapper.closeAttempt(tenant,requestNo,evidence,now)!=1)
            throw new TradeException("STATE_CONFLICT","尝试终态已改变");
        if(requestNo.equals(intent.activeAttemptNo())) {
            boolean closing=order.status().equals("CLOSING") || intent.closeFenceAt()!=null || !now.isBefore(order.expiresAt());
            if(mapper.clearActive(tenant,Long.parseLong(intent.intentId()),requestNo,closing?"CLOSED":"READY",closing?now:null,now)!=1)
                throw new TradeException("STATE_CONFLICT","活跃尝试已改变");
            if(closing) {
                orders.beginPaymentClose(tenant,Long.parseLong(order.orderId()));
                orders.completePaymentClose(tenant,Long.parseLong(order.orderId()));
            }
            outbox.append(tenant,"payment.attempt-closed.internal.v0",requestNo);
        } else if(intent.activeAttemptNo()==null && intent.status().equals("READY")
            && (order.status().equals("CLOSING") || !now.isBefore(order.expiresAt()))) {
            if(mapper.finishWithoutActive(tenant,Long.parseLong(intent.intentId()),now)!=1)
                throw new TradeException("STATE_CONFLICT","支付意图已改变");
            orders.beginPaymentClose(tenant,Long.parseLong(order.orderId()));
            orders.completePaymentClose(tenant,Long.parseLong(order.orderId()));
        }
    }
    private PaymentAttempt reference(long tenant,String requestNo) {
        return mapper.findAttempt(tenant,requestNo).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","支付尝试不存在"));
    }
}
