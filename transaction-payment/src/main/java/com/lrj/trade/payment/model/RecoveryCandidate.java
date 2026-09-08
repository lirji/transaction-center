package com.lrj.trade.payment.model;

/** 升级补漏只暴露不可变尝试引用，不读取或复制资金和渠道密钥。 */
public record RecoveryCandidate(String attemptId, String requestNo) {}
