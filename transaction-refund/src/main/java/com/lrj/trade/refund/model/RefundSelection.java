package com.lrj.trade.refund.model;

/** 申请指定金额归属，服务按冻结订单重验；商品数量为零允许仅金额售后调整。 */
public record RefundSelection(String componentType,String orderItemId,long quantity,long amount) {}
