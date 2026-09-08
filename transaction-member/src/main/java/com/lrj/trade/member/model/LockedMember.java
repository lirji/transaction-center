package com.lrj.trade.member.model;

/** 只在事务内使用的会员锁定视图，不能缓存为权限判断结果。 */
public record LockedMember(String id,String status,long version) {}
