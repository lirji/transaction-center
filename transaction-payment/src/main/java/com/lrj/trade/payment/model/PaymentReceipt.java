package com.lrj.trade.payment.model;

import java.time.Instant;

/** 每笔真实资金都保留，异常分类不等于正常订单履约。 */
public record PaymentReceipt(String receiptId,String receiptNo,String intentId,String attemptId,String orderId,String providerTransactionNo,long amount,String currency,Instant providerPaidAt,Instant confirmedAt,String disposition,String evidenceRef) {}
