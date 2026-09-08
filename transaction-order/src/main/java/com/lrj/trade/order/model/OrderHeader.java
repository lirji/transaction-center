package com.lrj.trade.order.model;
import java.time.Instant;

/** 订单内部锁定视图，paymentStartedAt是支付与取消竞争的持久屏障。 */
public record OrderHeader(String orderId,String orderNo,String memberId,String quoteId,String merchantNo,
    String warehouseId,String status,String financialStatus,long payableAmount,String currency,
    Instant expiresAt,Instant paymentStartedAt,Instant paidAt,Instant completedAt,Instant createdAt,long version) {}
