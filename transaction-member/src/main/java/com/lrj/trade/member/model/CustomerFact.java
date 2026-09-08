package com.lrj.trade.member.model;
import java.time.Instant;
/** 注册与不可逆首付事实的同一主库快照，事实版本不是历史MVCC版本。 */
public record CustomerFact(String memberId,Instant registeredAt,boolean everPaid,String firstPaidOrderId,Instant firstConfirmedAt,long factVersion) {}
