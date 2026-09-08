package com.lrj.trade.catalog;

import com.lrj.trade.core.Actor;
import com.lrj.trade.catalog.model.Quote;

/** 订单通过业务端口使用报价，不跨模块读取报价或商品数据表。 */
public interface QuotePort {
    /** 调用方必须有事务；锁定会员及可售商品，再验证报价归属和有效期。 */
    Quote requireUsable(Actor actor,long quoteId);
}
