package com.lrj.trade.refund.model;
import java.time.Instant;
import java.util.List;

/** 本人退款只读合同，不泄露渠道商户凭据、审批人身份或内部资金主键。 */
public record RefundView(String refundNo,String orderNo,long amount,String currency,String reason,String status,
    String approvalRef,long policyVersion,long version,Instant createdAt,List<RefundSelection> items,String businessNo) {
    /** 返回独立不可变明细容器。 */
    public RefundView { items=List.copyOf(items); }
    /** 普通日志不展开用户填写的退款原因或资金标识。 */
    @Override public String toString() { return "RefundView[REDACTED]"; }
}
