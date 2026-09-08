package com.lrj.trade.order;

import com.lrj.trade.order.model.Merchant;

/** 向交易域提供商户准入，不允许支付模块直接访问商户Mapper。 */
public interface MerchantTradingPort {
    /** 加入调用方事务共享锁定配置；缺失或冻结时禁止新的收款尝试。 */
    Merchant requireActive(long tenant);
}
