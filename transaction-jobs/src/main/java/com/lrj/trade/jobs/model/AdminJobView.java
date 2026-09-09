package com.lrj.trade.jobs.model;
import java.time.Instant;
import com.lrj.trade.core.query.AdminPage;
/** 管理查询的显式安全投影；不序列化资金凭据、个人信息或内部租约令牌。 */
public record AdminJobView(@com.fasterxml.jackson.annotation.JsonIgnore String cursor,String jobId,String jobType,String status,long attempts,Instant nextRunAt,Instant leaseUntil,boolean leaseExpired,String lastError,Instant createdAt,Instant updatedAt,long version) implements AdminPage.Row {
    /** 数值边界守卫保证浏览器不会静默舍入事实。 */
    public AdminJobView { com.lrj.trade.core.query.AdminNumbers.require(attempts);com.lrj.trade.core.query.AdminNumbers.require(version); }
}
