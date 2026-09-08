package com.lrj.trade.payment.model;

/** 原始真实收款及当前退款额度；异常收款独立可退，不增加正常订单消费额。 */
public record RefundSource(String receiptId,String receiptNo,String orderId,String attemptId,long amount,String currency,
    String disposition,long refundedAmount,long reservedAmount,String channelCode,String channelMerchantNo,String providerTransactionNo) {
    /** 渠道资金标识仅供内部编排，避免默认日志泄露。 */
    @Override public String toString() { return "RefundSource[REDACTED]"; }
}
