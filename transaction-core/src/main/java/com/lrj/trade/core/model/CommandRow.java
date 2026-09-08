package com.lrj.trade.core.model;

/** 事务锁定的幂等结果，不允许跨请求缓存以免读取旧状态。 */
public record CommandRow(String requestHash,String resultRef) {}
