package com.lrj.trade.order.model;
import java.time.Instant;
import java.util.List;

/** 本人订单查询合同，不包含收货地址明文或支付渠道凭据。 */
public record OrderView(String orderId,String orderNo,String quoteId,String merchantNo,String warehouseId,
    String status,String financialStatus,long payableAmount,String currency,Instant expiresAt,
    Instant paidAt,Instant completedAt,Instant createdAt,long version,List<OrderLine> items,String businessNo) {}
