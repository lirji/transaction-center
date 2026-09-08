package com.lrj.trade.order.model;

/** 持久化明细视图，预占键仅用于内部库存编排，不由客户端提供。 */
public record OrderItemRow(String itemId,String skuId,long skuRevision,String title,String specification,
    String stockType,long quantity,long unitPrice,long payableAmount,String reservationKey) {}
