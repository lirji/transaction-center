package com.lrj.trade.catalog.model;

/** 客户端只选择规格和数量，价格、币种及身份必须由服务端决定。 */
public record QuoteSelection(String skuId,Long quantity) {}
