package com.lrj.trade.refund.model;
import java.time.Instant;

/** 退款应用当前读快照；敏感身份摘要和证据不直接作为HTTP返回。 */
public record RefundRow(long refundId,String refundNo,String applicantKey,long memberId,long orderId,String orderNo,String receiptNo,String merchantRefundNo,long amount,String currency,String reason,String requestHash,String status,long policyVersion,long policyLimit,String approvalRef,Instant approvalDeadline,Long decisionVersion,String approvalActorKey,String approvalDecision,String approvalEvidence,String providerRefundNo,long version,Instant createdAt) {
    /** 日志不默认展开退款标识、原因或身份摘要。 */
    @Override public String toString() { return "RefundRow[REDACTED]"; }
}
