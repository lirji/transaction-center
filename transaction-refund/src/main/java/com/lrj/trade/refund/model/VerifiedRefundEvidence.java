package com.lrj.trade.refund.model;

import java.time.Instant;

/** 服务端适配器认证后的原始退款证据；类型名称不是验签证明，普通HTTP请求禁止直达。 */
public record VerifiedRefundEvidence(String channelCode,String channelMerchantNo,String eventId,
        String merchantRefundNo,String providerTransactionNo,String providerRefundNo,Kind kind,
        Long amount,String currency,Instant occurredAt,String signatureVersion,byte[] payload) {
    /** 终态失败必须证明原退款号永久不可再成功；不存在、普通失败响应和超时不满足。 */
    public enum Kind { SUCCEEDED, FAILED_FINAL }
    /** 复制原文防止验签后被共享数组篡改；具体字段和大小由耐久接收入口进一步约束。 */
    public VerifiedRefundEvidence {
        if(kind==null) throw new IllegalArgumentException("退款证据缺少类型");
        payload=payload==null?null:payload.clone();
    }
    /** 返回副本，防止接收后修改证据。 */
    @Override public byte[] payload() { return payload==null?null:payload.clone(); }
    /** 防止日志自动输出敏感原文与资金标识。 */
    @Override public String toString() { return "VerifiedRefundEvidence[REDACTED]"; }
}
