package com.lrj.trade.member.model;
import java.time.Instant;
/** 营销证据读取的领域快照，不包含个人明文、渠道密钥或可变实体。 */
public record MemberPaidEvidenceRow(String orderId,String memberId,long paidAmount,String currency,boolean first,String policy) {}
