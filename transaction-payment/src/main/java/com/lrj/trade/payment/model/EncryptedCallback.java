package com.lrj.trade.payment.model;

/** 密文带明确密钥版本，轮换后仍可定位历史解密材料。 */
public record EncryptedCallback(String keyVersion, byte[] ciphertext) {
    /** 防御性复制避免持久化过程中密文被调用者修改。 */
    public EncryptedCallback { ciphertext=ciphertext==null?null:ciphertext.clone(); }
    /** 密文只读副本，不允许外部替换已生成的数据。 */
    @Override public byte[] ciphertext() { return ciphertext==null?null:ciphertext.clone(); }
    /** 不将加密材料写入普通日志。 */
    @Override public String toString() { return "EncryptedCallback[REDACTED]"; }
}
