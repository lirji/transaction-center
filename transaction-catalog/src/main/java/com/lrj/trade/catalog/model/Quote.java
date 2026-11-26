package com.lrj.trade.catalog.model;

import java.time.Instant;
import java.util.List;

/** 完整报价只读合同，失效时刻等于当前时间时即不可再创建订单。 */
public record Quote(String quoteId,long payableAmount,String currency,String pricingPolicy,
                    Instant createdAt,Instant expiresAt,List<QuoteItem> items,String businessNo) {}
