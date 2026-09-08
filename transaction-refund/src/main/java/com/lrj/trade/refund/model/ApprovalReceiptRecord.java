package com.lrj.trade.refund.model;

/** 已受理审批的最小内部记录，原文只能认证解密，不允许日志展开。 */
public record ApprovalReceiptRecord(String receiptNo,String refundNo,String factHash,String payloadHash,byte[] cipher,String keyVersion) {
    /** 密文防御性复制防止外部修改已读取证据。 */
    public ApprovalReceiptRecord { cipher=cipher.clone(); }
    /** 返回独立密文副本。 */
    @Override public byte[] cipher() { return cipher.clone(); }
    /** 不泄漏业务编号或密文。 */
    @Override public String toString() { return "ApprovalReceiptRecord[REDACTED]"; }
}
