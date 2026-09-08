package com.lrj.trade.payment;

import com.lrj.trade.core.*;
import com.lrj.trade.payment.model.EncryptedCallback;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 外部版本密钥环的AES认证加密适配器；无默认密钥，不把本地配置伪称KMS。 */
@Component
public class AesCallbackPayloadProtector implements CallbackPayloadProtector {
    private final Map<String,SecretKeySpec> keys;
    private final String active;
    private final SecureRandom random=new SecureRandom();

    /** 配置格式版本:Base64逗号分隔，错误只报通用信息，严禁输出密钥内容。 */
    public AesCallbackPayloadProtector(@Value("${trade.callback.active-key-version:}") String active,
                                      @Value("${trade.callback.keys:}") String encodedKeys) {
        this.active=active;
        Map<String,SecretKeySpec> parsed=new HashMap<>();
        try {
            if(active.isBlank() && encodedKeys.isBlank()) { keys=Map.of(); return; }
            if(!active.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException();
            for(String entry:encodedKeys.split(",")) {
                String[] pair=entry.split(":",2);
                if(pair.length!=2 || !pair[0].matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException();
                byte[] raw=Base64.getDecoder().decode(pair[1]);
                try {
                    if(raw.length!=32 || parsed.putIfAbsent(pair[0],new SecretKeySpec(raw,"AES"))!=null)
                        throw new IllegalArgumentException();
                } finally { Arrays.fill(raw,(byte)0); }
            }
            if(!parsed.containsKey(active)) throw new IllegalArgumentException();
            this.keys=Map.copyOf(parsed);
        } catch(IllegalArgumentException error) { throw new IllegalArgumentException("回调加密配置无效"); }
    }

    /** 每条记录随机96位Nonce；认证数据包含不可变证据上下文和密钥版本。 */
    @Override
    public EncryptedCallback encrypt(String binding, byte[] payload) {
        Checks.text(binding,64);
        if(payload==null || payload.length==0 || payload.length>65536)
            throw new TradeException("INVALID_ARGUMENT","必要回调原文必须在1到65536字节之间");
        var key=key(active);
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD(aad(binding,active));
            byte[] body=cipher.doFinal(payload);
            return new EncryptedCallback(active,ByteBuffer.allocate(nonce.length+body.length).put(nonce).put(body).array());
        } catch(GeneralSecurityException error) { throw new IllegalStateException("回调加密失败"); }
    }

    /** 缺旧密钥可恢复重试，认证失败与密钥不可用必须区分，不能静默丢弃证据。 */
    @Override
    public byte[] decrypt(String binding, EncryptedCallback encrypted) {
        Checks.text(binding,64);
        if(encrypted==null || encrypted.ciphertext()==null || encrypted.ciphertext().length<29 || encrypted.ciphertext().length>65564)
            throw new TradeException("CALLBACK_EVIDENCE_INVALID","回调密文格式无效");
        var key=key(encrypted.keyVersion());
        try {
            byte[] bytes=encrypted.ciphertext();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
            cipher.updateAAD(aad(binding,encrypted.keyVersion()));
            return cipher.doFinal(bytes,12,bytes.length-12);
        } catch(GeneralSecurityException error) { throw new TradeException("CALLBACK_EVIDENCE_INVALID","回调证据认证失败"); }
    }

    private SecretKeySpec key(String version) {
        var key=version==null?null:keys.get(version);
        if(key==null) throw new TradeException("CALLBACK_ENCRYPTION_UNAVAILABLE","回调密钥版本不可用");
        return key;
    }

    private static byte[] aad(String binding,String version) {
        return Checks.hash("payment-callback-v1",binding,version).getBytes(StandardCharsets.UTF_8);
    }
}
