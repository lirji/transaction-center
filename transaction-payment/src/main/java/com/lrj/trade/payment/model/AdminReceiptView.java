package com.lrj.trade.payment.model;
import java.time.Instant;
import com.lrj.trade.core.query.AdminPage;
/** 管理查询的显式安全投影；不序列化资金凭据、个人信息或内部租约令牌。 */
public record AdminReceiptView(@com.fasterxml.jackson.annotation.JsonIgnore String cursor,String receiptNo,String paymentNo,String orderNo,String attemptNo,String channelCode,long amount,String currency,String disposition,Instant providerPaidAt,Instant confirmedAt,Instant createdAt,long refundedAmount,long refundReservedAmount,long unreservedAmount,long version,String businessNo) implements AdminPage.Row {
    /** 数值边界守卫保证浏览器不会静默舍入事实。 */
    public AdminReceiptView { com.lrj.trade.core.query.AdminNumbers.require(amount);com.lrj.trade.core.query.AdminNumbers.require(refundedAmount);com.lrj.trade.core.query.AdminNumbers.require(refundReservedAmount);com.lrj.trade.core.query.AdminNumbers.require(unreservedAmount);com.lrj.trade.core.query.AdminNumbers.require(version); }
}
