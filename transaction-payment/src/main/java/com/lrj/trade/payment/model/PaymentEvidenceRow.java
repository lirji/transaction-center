package com.lrj.trade.payment.model;
import java.time.Instant;
/** 营销证据读取的领域快照，不包含个人明文、渠道密钥或可变实体。 */
public record PaymentEvidenceRow(String orderId,String receiptNo,long paidAmount,long refundedAmount,long pendingRefundAmount,String currency) {}
