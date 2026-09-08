package com.lrj.trade.inventory;

import com.lrj.trade.core.Actor;

/** 订单组合库存的领域端口，禁止跨模块访问库存Mapper。 */
public interface InventoryTradingPort {
    /** 稳定预占键来自订单明细，加入调用方事务，失败时全部回滚。 */
    String reserve(Actor actor,String reservationKey,long warehouse,long sku,long quantity);
    /** 调用方必须先确认合法收款事实或零元订单，不能由前端直接调用。 */
    String confirm(Actor actor,String key,String reservationKey);
    /** 调用方必须有可靠关单屏障，不能依据本地TTL释放未决支付库存。 */
    String release(Actor actor,String key,String reservationKey);
}
