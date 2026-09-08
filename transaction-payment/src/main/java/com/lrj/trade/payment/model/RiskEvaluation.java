package com.lrj.trade.payment.model;

import java.time.Instant;

/** 风险输入与结果持久快照；同一评估编号冻结输入，过期时追加新评估保留历史。 */
public record RiskEvaluation(String evaluationNo,long tenantId,String attemptId,String requestNo,String orderId,
    String memberId,String channelCode,String channelMerchantNo,long amount,String currency,String inputHash,
    String state,String decisionId,String ruleVersion,String modelVersion,Instant validUntil,Instant requestedAt,Instant deadline) {
    /** 避免内部主体标识及风险元数据进入默认日志。 */
    @Override public String toString() { return "RiskEvaluation[REDACTED]"; }
}
