package com.lrj.trade.refund.model;

import java.time.Instant;

/** 后台处理队列最小视图，不返回申请人身份、自由文本原因、渠道凭据或资金内部引用。 */
public record RefundQueueItem(String cursor,String refundNo,String orderNo,long amount,String currency,
    String status,String approvalRef,Instant approvalDeadline,long version,String businessNo,String orderBusinessNo,String approvalBusinessNo) {
    /** 资金与审批关联号不展开到普通日志。 */
    @Override public String toString() { return "RefundQueueItem[REDACTED]"; }
}
