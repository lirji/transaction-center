package com.lrj.trade.catalog.model;

import java.util.Map;

/** 不可变报价明细，后续订单复制此快照而不是重新读取客户端金额。 */
public record QuoteItem(String skuId,long skuRevision,String title,Map<String,String> specification,
                        String stockType,long quantity,long unitPrice,long payableAmount) {}
