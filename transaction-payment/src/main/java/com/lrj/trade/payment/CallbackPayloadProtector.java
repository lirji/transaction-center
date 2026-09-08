package com.lrj.trade.payment;

import com.lrj.trade.payment.model.EncryptedCallback;

/** 回调证据保护端口，可替换为托管密钥实现，不将密钥传给Mapper或后台调度器。 */
public interface CallbackPayloadProtector {
    /** 绑定不可变上下文后加密，缺少密钥时拒绝接收而非回退明文。 */
    EncryptedCallback encrypt(String binding, byte[] payload);
    /** 使用记录中的历史版本解密并验证关联数据，不允许忽略认证失败。 */
    byte[] decrypt(String binding, EncryptedCallback encrypted);
}
