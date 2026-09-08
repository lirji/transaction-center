package com.lrj.trade.catalog.model;

import java.util.Map;

/** SKU视图中的revision是整体商品草稿版本或已发布版本，由查询入口确定。 */
public record Sku(String skuId,String skuNo,String productId,String title,long unitPrice,String currency,
                      String stockType,Map<String,String> specification,long revision) {}
