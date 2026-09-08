package com.lrj.trade.payment.model;

import java.time.Instant;

/** 持久化可信证据快照，原文仅密文存储，处理状态不覆盖原始事实。 */
public record CallbackRecord(String callbackId,
        String callbackNo,
        String attemptId,
        String channelCode,
        String channelMerchantNo,
        String eventId,
        String requestNo,
        String kind,
        String transactionNo,
        Long amount,
        String currency,
        Instant providerPaidAt,
        byte[] payloadHash,
        String factHash,
        byte[] payloadCipher,
        String keyVersion,
        String signatureVersion,
        String status,
        Instant receivedAt,
        Instant processedAt,
        String lastError) {
    /** 防御性复制密文与摘要，防止映射对象被修改。 */
    public CallbackRecord { payloadHash=payloadHash.clone(); payloadCipher=payloadCipher.clone(); }
    /** 摘要返回副本以保持记录不可变。 */
    @Override public byte[] payloadHash() { return payloadHash.clone(); }
    /** 密文返回副本，不泄露可修改的持久化引用。 */
    @Override public byte[] payloadCipher() { return payloadCipher.clone(); }
    /** 不把渠道证据自动打印进应用日志。 */
    @Override public String toString() { return "CallbackRecord[REDACTED]"; }
}
