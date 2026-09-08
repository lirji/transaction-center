package com.lrj.trade.payment.model;

import java.time.Instant;

/** 支付意图持久快照；成功收款指针不随重复回调变更。 */
public record PaymentIntent(String intentId,String paymentNo,String orderId,String memberId,String status,long expectedAmount,String currency,String activeAttemptNo,String succeededReceiptId,Instant closeFenceAt,long version) {}
