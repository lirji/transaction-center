package com.lrj.trade.inventory;
import com.lrj.trade.core.Actor;
/** 可信验收事务专用回库合同，原预占与累计数量由库存域独立约束。 */
public interface InventoryReturnPort {
    /** 验收不可售品也必须核验接收仓的租户归属。 */
    void requireWarehouse(long tenant,long warehouse);
    /** 调用方必须持有原订单与验收预算锁；操作编号永久绑定同一验收，不接受退款事件替代。 */
    String restock(Actor actor,String operation,String reservationKey,long sku,long originalQuantity,long warehouse,long quantity);
}
