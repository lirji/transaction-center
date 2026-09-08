package com.lrj.trade.catalog.model;

import java.time.Instant;

/** 报价主表持久化视图，查询必须额外匹配所属会员和租户。 */
public record QuoteHeader(String quoteId,long payableAmount,String currency,String pricingPolicy,
                          Instant createdAt,Instant expiresAt) {}
