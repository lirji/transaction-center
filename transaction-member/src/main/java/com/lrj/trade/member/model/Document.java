package com.lrj.trade.member.model;

/** 条款版本只读视图，URL的展示内容应保持与已发布摘要一致。 */
public record Document(String id,String purpose,String documentVersion,String title,String contentUrl,String contentHash) {}
