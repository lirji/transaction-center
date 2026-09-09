package com.lrj.trade.catalog.model;
import java.time.Instant;
import com.lrj.trade.core.query.AdminPage;
/** 管理查询的显式安全投影；不序列化资金凭据、个人信息或内部租约令牌。 */
public record AdminQuoteView(@com.fasterxml.jackson.annotation.JsonIgnore String cursor,String quoteId,String memberId,long payableAmount,String currency,String pricingPolicy,Instant createdAt,Instant expiresAt,long version) implements AdminPage.Row {
    /** 数值边界守卫保证浏览器不会静默舍入事实。 */
    public AdminQuoteView { com.lrj.trade.core.query.AdminNumbers.require(payableAmount);com.lrj.trade.core.query.AdminNumbers.require(version); }
}
