package com.lrj.trade.order.model;
import java.time.Instant;
import java.util.Map;
/** 管理详情的安全子项，仅包含运营需要的事实，不包含原始证据。 */
public record AdminOrderItem(String itemId,String skuId,long skuRevision,String title,Map<String,String> specification,String stockType,long quantity,long unitPrice,long payableAmount,long returnedQuantity) {
    /** 数值边界守卫保证浏览器不会静默舍入事实。 */
    public AdminOrderItem { com.lrj.trade.core.query.AdminNumbers.require(skuRevision);com.lrj.trade.core.query.AdminNumbers.require(quantity);com.lrj.trade.core.query.AdminNumbers.require(unitPrice);com.lrj.trade.core.query.AdminNumbers.require(payableAmount);com.lrj.trade.core.query.AdminNumbers.require(returnedQuantity); }
}
