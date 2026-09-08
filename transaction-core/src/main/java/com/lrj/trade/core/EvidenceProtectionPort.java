package com.lrj.trade.core;

/** 跨领域必要证据保护合同；命名空间与不可变上下文共同防止跨用途密文移植。 */
public interface EvidenceProtectionPort {
    /** 缺少密钥必须失败，不允许退回明文保存。 */
    Envelope encrypt(String namespace,String binding,byte[] payload);
    /** 按历史密钥版本解密并验证上下文，认证失败不得忽略。 */
    byte[] decrypt(String namespace,String binding,Envelope envelope);
    /** 只读密文及版本，不包含密钥或业务解释。 */
    record Envelope(String keyVersion,byte[] ciphertext) {
        /** 复制调用方数据，防止认证后被修改。 */
        public Envelope { ciphertext=ciphertext==null?null:ciphertext.clone(); }
        /** 返回独立副本，避免暴露可修改的内部数组。 */
        @Override public byte[] ciphertext() { return ciphertext==null?null:ciphertext.clone(); }
        /** 禁止默认日志输出加密材料。 */
        @Override public String toString() { return "EvidenceEnvelope[REDACTED]"; }
    }
}
