package com.lrj.trade.order.model;

import java.time.Instant;

/** 已持订单锁后读取永久履约事实，不将证据引用或身份摘要直接返回普通会员。 */
public record FulfillmentRecord(String fulfillmentNo,long orderId,String deliveryHash,Instant deliveredAt,String completionHash,Instant completedAt,long version) {}
