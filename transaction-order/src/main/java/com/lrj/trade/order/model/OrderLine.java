package com.lrj.trade.order.model;
import java.util.Map;

/** 订单商品历史快照，不向调用方暴露内部预占操作键。 */
public record OrderLine(String itemId,String skuId,long skuRevision,String title,Map<String,String> specification,
    String stockType,long quantity,long unitPrice,long payableAmount) {}
