package com.lrj.trade.refund.model;

import com.lrj.trade.core.Actor;
import java.time.Instant;

/** 验签后的内部审批证据；必须持久化并原子受理后才能确认，不能直接当成资金派发许可。 */
public record VerifiedApprovalReceipt(String sourceIssuer,String sourceSubject,String eventId,Actor approver,String refundNo,String approvalRef,
    long decisionVersion,boolean approve,String evidenceRef,Instant issuedAt,Instant expiresAt,String signedReceipt) {
    /** 禁止日志展开JWT原文、审批人及业务关联。 */
    @Override public String toString() { return "VerifiedApprovalReceipt[REDACTED]"; }
}
