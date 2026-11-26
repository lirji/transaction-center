package com.lrj.trade.order.model;
import java.time.Instant;
import java.util.Map;
/** 管理详情的安全子项，仅包含运营需要的事实，不包含原始证据。 */
public record AdminFulfillmentView(String fulfillmentNo,Instant deliveredAt,Instant completedAt,long version,String businessNo) {
    /** 数值边界守卫保证浏览器不会静默舍入事实。 */
    public AdminFulfillmentView { com.lrj.trade.core.query.AdminNumbers.require(version); }
}
