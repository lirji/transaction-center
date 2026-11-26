package com.lrj.trade.payment.model;
import java.time.Instant;
import com.lrj.trade.core.query.AdminPage;
/** 管理查询的显式安全投影；不序列化资金凭据、个人信息或内部租约令牌。 */
public record AdminPaymentView(@com.fasterxml.jackson.annotation.JsonIgnore String cursor,String paymentNo,String orderId,String orderNo,String memberId,String status,long expectedAmount,String currency,String activeAttemptNo,Instant createdAt,Instant updatedAt,long version,String succeededReceiptNo,Instant closeFenceAt,String businessNo) implements AdminPage.Row {
    /** 数值边界守卫保证浏览器不会静默舍入事实。 */
    public AdminPaymentView { com.lrj.trade.core.query.AdminNumbers.require(expectedAmount);com.lrj.trade.core.query.AdminNumbers.require(version); }
}
