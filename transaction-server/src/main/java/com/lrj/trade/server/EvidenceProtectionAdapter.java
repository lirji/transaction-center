package com.lrj.trade.server;

import com.lrj.trade.core.*;
import com.lrj.trade.payment.CallbackPayloadProtector;
import com.lrj.trade.payment.model.EncryptedCallback;
import org.springframework.stereotype.Component;

/** 装配层复用已有证据加密端口；退款不反向引用支付实现，历史支付密文格式保持不变。 */
@Component
public class EvidenceProtectionAdapter implements EvidenceProtectionPort {
    private final CallbackPayloadProtector delegate;
    /** 依赖已有密钥保护抽象，不接触原始密钥配置。 */
    public EvidenceProtectionAdapter(CallbackPayloadProtector delegate) { this.delegate=delegate; }
    /** 额外绑定用途，不能把其他业务密文移植成退款证据。 */
    @Override public Envelope encrypt(String namespace,String binding,byte[] payload) {
        var encrypted=delegate.encrypt(context(namespace,binding),payload);
        return new Envelope(encrypted.keyVersion(),encrypted.ciphertext());
    }
    /** 保留旧密钥和认证失败的错误分类，不包装供应商敏感异常。 */
    @Override public byte[] decrypt(String namespace,String binding,Envelope envelope) {
        if(envelope==null) throw new TradeException("CALLBACK_EVIDENCE_INVALID","证据密文不能为空");
        return delegate.decrypt(context(namespace,binding),new EncryptedCallback(envelope.keyVersion(),envelope.ciphertext()));
    }
    private String context(String namespace,String binding) {
        Checks.text(namespace,64); Checks.text(binding,64);
        return Checks.hash("domain-evidence-v1",namespace,binding);
    }
}
