package com.lrj.trade.payment;
import com.lrj.trade.payment.model.PaymentDispatch;
/** 只读查询计划，绝不调用创建许可、风险评估或关闭屏障推进。 */
public interface PaymentQueryPlanPort {
    /** 从原支付请求或收款引用校验租户/原channelId后生成固定QUERY。 */
    PaymentDispatch query(long tenant,long channel,String referenceType,String reference);
    /** 已派发退款的原资金归属，不暴露凭据。 */
    RefundSource refundSource(long tenant,long channel,String refundNo);
    /** 所有值来自同次原绑定查询，退款应用还须核原申请编号和金额。 */
    record RefundSource(String receiptNo,String attemptId,String channelCode,String channelMerchantNo,String providerTransactionNo,long amount,String currency,String bindingCurrency,boolean submitted) {
        /** 日志不展开商户和资金交易标识。 */
        @Override public String toString() {return "RefundQuerySource[REDACTED]";}
    }
}
