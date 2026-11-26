package com.lrj.trade.catalog.model;

/** 仅供管理端使用的商品分页投影，主规格保证每个商品只占一行。 */
public record AdminProduct(String productId,String skuId,String name,String status,long version,long unitPrice,
                           String currency,Long publishedRevision,String productNo) {}
