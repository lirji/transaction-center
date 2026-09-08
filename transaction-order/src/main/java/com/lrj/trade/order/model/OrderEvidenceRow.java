package com.lrj.trade.order.model;
import java.time.Instant;
/** 营销证据读取的领域快照，不包含个人明文、渠道密钥或可变实体。 */
public record OrderEvidenceRow(String orderId,String orderNo,String memberId,String status,String financialStatus,long payableAmount,String currency,Instant paidAt,Instant completedAt,long evidenceVersion) {}
