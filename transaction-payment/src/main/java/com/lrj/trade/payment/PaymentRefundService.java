package com.lrj.trade.payment;

import com.lrj.trade.core.*;
import com.lrj.trade.order.OrderPaymentPort;
import com.lrj.trade.payment.mapper.*;
import com.lrj.trade.payment.model.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 永久退款资金预占；应用状态、审批与fencing由外层同事务组合，服务本身不发网络。 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class PaymentRefundService implements PaymentRefundPort {
    private final PaymentRefundMapper mapper;
    private final PaymentMapper payments;
    private final OrderPaymentPort orders;
    private final Outbox outbox;
    private final Clock clock;

    /** 使用支付域自己的Mapper与订单公开锁端口，不向退款模块泄露持久化实现。 */
    public PaymentRefundService(PaymentRefundMapper mapper,PaymentMapper payments,OrderPaymentPort orders,Outbox outbox,Clock clock) {
        this.mapper=mapper; this.payments=payments; this.orders=orders; this.outbox=outbox; this.clock=clock;
    }

    /** 正常退款从意图成功指针定位，不能误把多付或金额异常当成订单净消费。 */
    @Override public RefundSource lockNormalSource(long tenant,long order) {
        Checks.positive(tenant); Checks.positive(order); orders.lockPaymentOrder(tenant,order);
        var intent=payments.lockByOrder(tenant,order).stream().findFirst()
            .orElseThrow(()->new TradeException("REFUND_NOT_PAID","订单尚无正常实收"));
        if(intent.succeededReceiptId()==null) throw new TradeException("REFUND_NOT_PAID","订单尚无正常实收");
        var source=mapper.lockSourceById(tenant,Long.parseLong(intent.succeededReceiptId())).stream().findFirst()
            .orElseThrow(()->new TradeException("STATE_CONFLICT","正常收款引用缺失"));
        if(!source.orderId().equals(Long.toString(order)) || !source.disposition().equals("NORMAL")
            || source.amount()!=intent.expectedAmount() || !source.currency().equals(intent.currency()))
            throw new TradeException("REFUND_BINDING_CONFLICT","正常实收指针归属或金额不一致");
        return source;
    }

    /** 初读只定位不变归属；真正额度判断必须在订单/意图/收款锁之后。 */
    @Override public RefundSource lockSource(long tenant,String receiptNo) {
        Checks.positive(tenant); Checks.text(receiptNo,64);
        var reference=mapper.findSource(tenant,receiptNo).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","真实收款不存在"));
        orders.lockPaymentOrder(tenant,Long.parseLong(reference.orderId()));
        if(payments.lockByOrder(tenant,Long.parseLong(reference.orderId())).isEmpty())
            throw new TradeException("STATE_CONFLICT","收款所属支付意图不存在");
        return mapper.lockSource(tenant,receiptNo).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","真实收款不存在"));
    }

    /** 重复退款号只返回原状态，不二次占款；换来源或金额不能复用同一退款号。 */
    @Override public RefundHold reserve(long tenant,String refundNo,String receiptNo,long orderId,long amount,String currency) {
        Checks.text(refundNo,64); Checks.positive(orderId); requireMoney(amount,currency);
        var source=lockSource(tenant,receiptNo);
        if(!source.orderId().equals(Long.toString(orderId)) || !source.currency().equals(currency))
            throw new TradeException("REFUND_BINDING_CONFLICT","退款来源订单或币种不一致");
        var now=now(); String reservation="RFH-"+UUID.randomUUID();
        // 先占永久唯一键，再当前读；不先锁缺失退款号导致不同订单互持插入间隙锁。
        mapper.insertHold(tenant,reservation,refundNo,Long.parseLong(source.receiptId()),source.receiptNo(),orderId,
            amount,currency,source.channelCode(),source.channelMerchantNo(),now);
        var hold=mapper.lockHold(tenant,refundNo).getFirst();
        if(!hold.receiptId().equals(source.receiptId()) || !hold.orderId().equals(source.orderId())
            || hold.amount()!=amount || !hold.currency().equals(currency))
            throw new TradeException("REFUND_BINDING_CONFLICT","退款编号已经绑定其他资金请求");
        if(!reservation.equals(hold.reservationNo())) return hold;
        if(mapper.reserveAmount(tenant,Long.parseLong(source.receiptId()),amount,currency,now)!=1)
            throw new TradeException("REFUND_AMOUNT_EXCEEDED","退款成功及预占总额不能超过实收");
        outbox.append(tenant,"payment.refund-held.internal.v0",refundNo);
        return hold;
    }

    /** 此标记必须与退款审批/应用状态/任务fencing一起提交，不能单独作为渠道执行授权。 */
    @Override public RefundHold markSubmitted(long tenant,String refundNo) {
        var hold=lockExisting(tenant,refundNo).hold();
        if(hold.state().equals("SUBMITTED") || hold.state().equals("SUCCEEDED")) return hold;
        if(!hold.state().equals("HELD")) throw new TradeException("REFUND_TERMINAL_CONFLICT","已释放退款不能派发");
        changed(mapper.markSubmitted(tenant,refundNo,now()));
        return mapper.lockHold(tenant,refundNo).getFirst();
    }

    /** 本地拒绝只适用于从未派发；未知渠道状态必须继续占用额度。 */
    @Override public RefundHold releaseUnsubmitted(long tenant,String refundNo,String evidenceRef) {
        return release(tenant,refundNo,evidenceRef,false);
    }

    /** 调用方必须已完成可靠终态与原渠道绑定验证，普通超时/查不到不能调用。 */
    @Override public RefundHold releaseVerifiedFailure(long tenant,String refundNo,String evidenceRef) {
        return release(tenant,refundNo,evidenceRef,true);
    }

    private RefundHold release(long tenant,String refundNo,String evidence,boolean verifiedFailure) {
        Checks.text(evidence,255);
        var hold=lockExisting(tenant,refundNo).hold();
        if(hold.state().equals("RELEASED")) return hold;
        if(hold.state().equals("SUCCEEDED") || (!verifiedFailure && !hold.state().equals("HELD"))
            || (verifiedFailure && !hold.state().equals("SUBMITTED")))
            throw new TradeException("REFUND_TERMINAL_CONFLICT","不能按此证明释放退款额度");
        var now=now();
        changed(mapper.releaseHold(tenant,refundNo,hold.state(),evidence,now));
        changed(mapper.releaseAmount(tenant,Long.parseLong(hold.receiptId()),hold.amount(),now));
        outbox.append(tenant,"payment.refund-released.internal.v0",refundNo);
        return mapper.lockHold(tenant,refundNo).getFirst();
    }

    /** 原始可信成功必须先耐久接收；金额/归属冲突留给上层人工核对，不能丢弃原始证据。 */
    @Override public RefundHold confirmSucceeded(long tenant,String refundNo,String providerRefundNo,long amount,String currency,
        Instant providerSucceededAt,String evidenceRef) {
        Checks.text(providerRefundNo,255); Checks.text(evidenceRef,255); requireMoney(amount,currency);
        if(providerSucceededAt==null) throw new TradeException("INVALID_ARGUMENT","退款成功时间不能为空");
        // 数据库保留毫秒精度；重放可有不同证据引用，但不能悄悄改变原渠道资金发生时间。
        var occurred=providerSucceededAt.truncatedTo(ChronoUnit.MILLIS);
        var hold=lockExisting(tenant,refundNo).hold();
        if(hold.amount()!=amount || !hold.currency().equals(currency))
            throw new TradeException("REFUND_FACT_CONFLICT","渠道退款金额或币种与冻结请求不一致");
        if(hold.state().equals("SUCCEEDED")) {
            if(!providerRefundNo.equals(hold.providerRefundNo()) || !occurred.equals(hold.providerSucceededAt()))
                throw new TradeException("REFUND_FACT_CONFLICT","退款编号已有其他渠道成功事实");
            return hold;
        }
        if(hold.state().equals("RELEASED"))
            throw new TradeException("REFUND_TERMINAL_CONFLICT","可靠释放后矛盾成功需保留原始证据人工核对");
        var now=now();
        try {
            changed(mapper.confirmHold(tenant,refundNo,providerRefundNo,occurred,evidenceRef,now));
        } catch(DuplicateKeyException duplicateProviderRefund) {
            throw new TradeException("REFUND_FACT_CONFLICT","渠道退款号已经绑定其他退款");
        }
        changed(mapper.confirmAmount(tenant,Long.parseLong(hold.receiptId()),amount,now));
        mapper.insertLedger(tenant,Long.parseLong(hold.receiptId()),refundNo,hold.reservationNo(),"ML-"+UUID.randomUUID(),
            amount,currency,evidenceRef,now);
        outbox.append(tenant,"payment.refund-succeeded.internal.v0",refundNo);
        // 不操作会员everPaid、订单履约或库存；退款成功不是退货验收事实。
        return mapper.lockHold(tenant,refundNo).getFirst();
    }

    private Locked lockExisting(long tenant,String refundNo) {
        Checks.positive(tenant); Checks.text(refundNo,64);
        var reference=mapper.findHold(tenant,refundNo).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","退款资金预占不存在"));
        var source=lockSource(tenant,reference.receiptNo());
        var hold=mapper.lockHold(tenant,refundNo).getFirst();
        if(!hold.receiptId().equals(source.receiptId()) || !hold.orderId().equals(source.orderId())
            || !hold.currency().equals(source.currency()) || !hold.channelCode().equals(source.channelCode())
            || !hold.channelMerchantNo().equals(source.channelMerchantNo()))
            throw new TradeException("REFUND_BINDING_CONFLICT","退款资金与原渠道归属不一致");
        return new Locked(hold,source);
    }
    private void changed(int rows) {
        if(rows!=1) throw new TradeException("STATE_CONFLICT","退款资金状态或预占额度已改变");
    }
    private void requireMoney(long amount,String currency) {
        if(amount<=0 || amount>9007199254740991L || currency==null || !currency.matches("[A-Z]{3}"))
            throw new TradeException("INVALID_ARGUMENT","退款金额或币种无效");
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    /** 锁内来源和预占快照，不向网络适配器暴露数据库连接。 */
    private record Locked(RefundHold hold,RefundSource source) {}
}
