package com.lrj.trade.member.model;

/** 会员只读视图；不开放未经验证的消费事实修改接口。 */
public record Member(String memberId, String displayName, String status, String registeredAt, boolean everPaid,long version) {}
