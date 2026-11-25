package com.lrj.trade.catalog;

import com.lrj.trade.core.Actor;
import com.lrj.trade.catalog.model.CheckoutPrice;
import com.lrj.trade.catalog.model.Quote;

/** 订单通过业务端口使用报价，不跨模块读取报价或商品数据表。 */
public interface QuotePort {
    /** 调用方必须有事务；锁定会员及可售商品，再验证报价归属和有效期。 */
    Quote requireUsable(Actor actor,long quoteId);

    /** 用已验证的试点价格创建原生报价，与对应订单在同一个交易事务内提交。 */
    Quote createCheckout(Actor actor, CheckoutPrice price);
}
