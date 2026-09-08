package com.lrj.trade.refund;
import com.lrj.trade.core.*;
import com.lrj.trade.payment.PaymentQueryPlanPort;
import com.lrj.trade.refund.mapper.RefundMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 拼接原申请与资金只读事实，不调用prepare、expire或同号补发方法。 */
@Service
public class RefundQueryPlanService implements RefundQueryPlanPort {
    private final RefundMapper mapper;private final PaymentQueryPlanPort payments;
    /** 通过Port取支付域原归属，禁止跨域Mapper读写。 */
    public RefundQueryPlanService(RefundMapper mapper,PaymentQueryPlanPort payments) {this.mapper=mapper;this.payments=payments;}
    /** 历史成功失败也可查询，但未派发/币种不符合原渠道合同则明确拒绝。 */
    @Override @Transactional(readOnly=true,timeout=5)
    public RefundExecutionPort.Plan query(long tenant,long channel,String refundNo) {
        Checks.positive(tenant);Checks.positive(channel);Checks.text(refundNo,64);
        var rows=mapper.findRefund(tenant,refundNo);if(rows.size()!=1) throw new TradeException("NOT_FOUND","原退款申请不存在");
        var app=rows.getFirst();var source=payments.refundSource(tenant,channel,refundNo);
        if(!source.submitted() || !source.receiptNo().equals(app.receiptNo()) || source.amount()!=app.amount() || !source.currency().equals(app.currency()) || !source.currency().equals(source.bindingCurrency()))
            throw new TradeException("RECONCILIATION_QUERY_UNAVAILABLE","退款尚未派发或原渠道合同不支持该查询");
        return new RefundExecutionPort.Plan(tenant,app.refundNo(),app.merchantRefundNo(),source.receiptNo(),source.attemptId(),source.channelCode(),source.channelMerchantNo(),source.providerTransactionNo(),app.amount(),app.currency(),"QUERY");
    }
}
