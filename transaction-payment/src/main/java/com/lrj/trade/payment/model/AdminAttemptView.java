package com.lrj.trade.payment.model;
import java.time.Instant;
import com.lrj.trade.core.query.AdminPage;
/** 管理查询的显式安全投影；不序列化资金凭据、个人信息或内部租约令牌。 */
public record AdminAttemptView(@com.fasterxml.jackson.annotation.JsonIgnore String cursor,String attemptId,String paymentNo,String attemptNo,String channelId,String channelCode,String status,Instant submittedAt,Instant closedAt,boolean hasCloseEvidence,Instant createdAt,Instant updatedAt,long version) implements AdminPage.Row {
    /** 数值边界守卫保证浏览器不会静默舍入事实。 */
    public AdminAttemptView { com.lrj.trade.core.query.AdminNumbers.require(version); }
}
