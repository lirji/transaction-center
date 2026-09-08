package com.lrj.trade.catalog.model;

/** 展示视图的价格只用于浏览，后续下单仍需服务端报价。 */
public record Product(String productId,String skuId,String name,String status,long version,long unitPrice,String currency,Long publishedRevision) {}
