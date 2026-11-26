package com.lrj.trade.order.model;
import java.time.Instant;
import com.lrj.trade.core.query.AdminPage;
/** 管理查询的显式安全投影；不序列化资金凭据、个人信息或内部租约令牌。 */
public record AdminOrderView(@com.fasterxml.jackson.annotation.JsonIgnore String cursor,String orderId,String orderNo,String memberId,String quoteId,String merchantNo,String warehouseId,String status,String financialStatus,long payableAmount,String currency,Instant expiresAt,Instant paidAt,Instant completedAt,Instant createdAt,long version,Instant paymentStartedAt,Instant cancelledAt,Instant updatedAt,String businessNo) implements AdminPage.Row {
    /** 数值边界守卫保证浏览器不会静默舍入事实。 */
    public AdminOrderView { com.lrj.trade.core.query.AdminNumbers.require(payableAmount);com.lrj.trade.core.query.AdminNumbers.require(version); }
}
