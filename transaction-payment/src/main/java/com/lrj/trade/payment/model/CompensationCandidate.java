package com.lrj.trade.payment.model;

/** 有界升级扫描只携带不可变收款定位与分类，不复制渠道凭据或原始证据。 */
public record CompensationCandidate(long receiptId,String receiptNo,String disposition) {}
