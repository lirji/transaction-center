package com.lrj.trade.payment.model;

import java.time.Instant;

/** 一笔退款永久资金预占记录，不替代退款申请、审批或渠道回调耐久信箱。 */
public record RefundHold(String reservationNo,String refundNo,String receiptId,String receiptNo,String orderId,long amount,
    String currency,String channelCode,String channelMerchantNo,String state,String providerRefundNo,
    Instant submittedAt,Instant providerSucceededAt,Instant confirmedAt,Instant releasedAt,String evidenceRef) {
    /** 默认不打印资金标识或证据引用。 */
    @Override public String toString() { return "RefundHold[REDACTED]"; }
}
