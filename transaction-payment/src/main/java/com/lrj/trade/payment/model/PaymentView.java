package com.lrj.trade.payment.model;

/** 本人支付进度；未生成渠道操作时明确返回本地状态，不伪造支付链接。 */
public record PaymentView(String paymentNo,String orderId,String status,long expectedAmount,String currency,
    String activeAttemptNo,String attemptStatus,long version) {}
