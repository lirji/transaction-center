package com.lrj.trade.catalog;

import com.lrj.trade.catalog.model.QuoteItem;
import com.lrj.trade.catalog.model.Sku;

/** 定价策略扩展点；当前只实现固定价，未来优惠必须先有可信外部合同。 */
public interface PricingPolicy {
    /** 策略版本写入报价，使历史金额能追溯到确定规则。 */
    String version();
    /** 只接收服务端发布快照，不能让客户端指定价格或币种。 */
    QuoteItem price(Sku sku,long quantity);
}
