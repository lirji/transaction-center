package com.lrj.trade.refund.model;
import java.time.Instant;
import java.util.Map;
/** 管理详情的安全子项，仅包含运营需要的事实，不包含原始证据。 */
public record AdminRefundItem(String componentType,String orderItemId,long quantity,long amount) {
    /** 数值边界守卫保证浏览器不会静默舍入事实。 */
    public AdminRefundItem { com.lrj.trade.core.query.AdminNumbers.require(quantity);com.lrj.trade.core.query.AdminNumbers.require(amount); }
}
