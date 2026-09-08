package com.lrj.trade.payment.model;

import java.time.Instant;

/** 一次渠道尝试的稳定身份及不可逆关闭证明。 */
public record PaymentAttempt(String attemptId,String intentId,String orderId,String channelId,String requestNo,String status,Instant submittedAt,Instant closedAt,String closeEvidenceRef,long version) {}
