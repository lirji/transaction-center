package com.lrj.trade.payment.model;

import java.time.Instant;

/** 可信渠道适配器完成验签后的输入；普通客户端绝不能构造此对象直接改变资金。 */
public record VerifiedPaymentCallback(String channelCode, String channelMerchantNo, String eventId,
        String requestNo, String kind, String transactionNo, Long amount, String currency,
        Instant providerPaidAt, String signatureVersion, byte[] payload) {
    /** 防止调用方在验签后修改共享数组；原文仅保存必要且已受限的数据。 */
    public VerifiedPaymentCallback { payload=payload==null?null:payload.clone(); }
    /** 返回副本，保持验签输入不可变。 */
    @Override public byte[] payload() { return payload==null?null:payload.clone(); }
    /** 日志不能隐式输出渠道原文或敏感交易信息。 */
    @Override public String toString() { return "VerifiedPaymentCallback[REDACTED]"; }
}
