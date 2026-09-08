package com.lrj.trade.order.model;

/** 租户逻辑商户视图，不包含渠道账户或密钥。 */
public record Merchant(String merchantId,String merchantNo,String name,String status,long version) {}
