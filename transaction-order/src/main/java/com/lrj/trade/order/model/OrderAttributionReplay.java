package com.lrj.trade.order.model;

/** 永久原命令成功引用，不依赖短期Commands保留期限。 */
public record OrderAttributionReplay(String requestHash,String orderNo) {}
