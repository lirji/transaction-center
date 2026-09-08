package com.lrj.trade.client;

/** 保留服务端原始错误和不透明审计号，未知新码不能导致反序列化失败。 */
public record TradeFailure(String code, String message, String traceId, boolean retryable) {}
