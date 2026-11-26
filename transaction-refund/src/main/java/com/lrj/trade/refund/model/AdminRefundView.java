package com.lrj.trade.refund.model;
import java.time.Instant;
import com.lrj.trade.core.query.AdminPage;
/** 管理查询的显式安全投影；不序列化资金凭据、个人信息或内部租约令牌。 */
public record AdminRefundView(@com.fasterxml.jackson.annotation.JsonIgnore String cursor,String refundNo,String orderNo,String memberId,long amount,String currency,String status,String approvalRef,Instant approvalDeadline,long policyVersion,long version,Instant createdAt,String businessNo,String orderBusinessNo,String approvalBusinessNo) implements AdminPage.Row {
    /** 数值边界守卫保证浏览器不会静默舍入事实。 */
    public AdminRefundView { com.lrj.trade.core.query.AdminNumbers.require(amount);com.lrj.trade.core.query.AdminNumbers.require(policyVersion);com.lrj.trade.core.query.AdminNumbers.require(version); }
}
