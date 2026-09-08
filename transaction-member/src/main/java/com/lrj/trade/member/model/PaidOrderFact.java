package com.lrj.trade.member.model;

import java.time.Instant;

/** 每笔正常足额支付订单的不可变消费认定；回放返回原版本而不是当前聚合版本。 */
public record PaidOrderFact(String memberId,String orderId,long paidAmount,String currency,Instant confirmedAt,
    boolean firstConfirmedPaidOrder,String firstOrderPolicy,long factVersion) {}
