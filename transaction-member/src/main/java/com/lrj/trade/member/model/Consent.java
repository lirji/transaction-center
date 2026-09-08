package com.lrj.trade.member.model;

/** 可审计的同意历史；当前状态取对应用途的最大memberVersion事件。 */
public record Consent(String id,String purpose,String documentVersion,String action,long memberVersion,String occurredAt) {}
