package com.lrj.trade.refund.model;

/** 内部证据绑定快照，不向客户端开放原资金路由。 */
public record RefundEvidenceBinding(String refundNo,String merchantRefundNo,String channelCode,
        String channelMerchantNo,String providerTransactionNo,long amount,String currency) {
    /** 默认日志不泄露资金标识。 */
    @Override public String toString() { return "RefundEvidenceBinding[REDACTED]"; }
}
