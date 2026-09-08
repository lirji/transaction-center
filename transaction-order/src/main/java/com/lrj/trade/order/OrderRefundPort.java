package com.lrj.trade.order;

import com.lrj.trade.core.Actor;
import java.util.List;

/** 退款只能读取订单公开的冻结金额与归属，不能访问订单Mapper或重新计算当前商品价。 */
public interface OrderRefundPort {
    /** 外层事务内按本人归属锁订单；冻结会员仍能申请合法售后。 */
    Snapshot lockOwn(Actor actor,String orderNo);
    /** 仅供可信退款恢复按不变订单主键锁定，不执行会员状态判断。 */
    Snapshot lockTrusted(long tenant,long order);
    /** 同事务更新资金展示状态及证据版本，不改变履约、首单或库存。 */
    Change recordRefundChange(long tenant,long order,long refundedAmount);
    /** 锁内成功推进后的订单身份和证据版本，不是退款申请或会员版本。 */
    record Change(String orderId,String memberId,long evidenceVersion) {}
    /** 冻结订单和明细快照，不暴露地址或库存内部操作键。 */
    record Snapshot(String orderId,String orderNo,String memberId,long payableAmount,String currency,
        long freightAmount,long taxAmount,List<Item> items) {
        /** 防止调用方修改冻结明细容器。 */
        public Snapshot { items=List.copyOf(items); }
    }
    /** 商品数量仅描述退款涉及范围，实物退货必须另外按履约数量验收。 */
    record Item(String itemId,long quantity,long payableAmount) {}
    /** 从订单读取已冻结附加收费，不允许请求自行增加运费或税的可退金额。 */
    record Charges(long freightAmount,long taxAmount) {}
}
