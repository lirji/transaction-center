package com.lrj.trade.order;

import com.lrj.trade.core.*;
import com.lrj.trade.order.model.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM地址适配器，密钥必须外部注入；未配置时拒绝实物下单。 */
@Component
public class AesAddressProtector implements AddressProtector {
    private final SecretKeySpec key;
    private final String version;
    private final SecureRandom random=new SecureRandom();
    /** 缺配置允许其他非地址业务启动，错误配置直接失败且不输出密钥内容。 */
    public AesAddressProtector(@Value("${trade.address.key-base64:}") String encoded,
                               @Value("${trade.address.key-version:}") String version) {
        this.version=version;
        if(encoded.isBlank() && version.isBlank()) { this.key=null; return; }
        try {
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if(bytes.length!=32 || version.isBlank() || version.length()>64) throw new IllegalArgumentException();
            this.key=new SecretKeySpec(bytes,"AES");
            java.util.Arrays.fill(bytes,(byte)0);
        } catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("地址加密配置无效"); }
    }
    /** 每字段使用独立随机Nonce和用途绑定，验证失败不能回退明文。 */
    @Override
    public EncryptedAddress encrypt(long tenant,String orderNo,ShippingAddress address) {
        if(key==null) throw new TradeException("ADDRESS_ENCRYPTION_UNAVAILABLE","地址加密尚未配置");
        if(address==null) throw new TradeException("INVALID_ARGUMENT","实物订单必须提供收货地址");
        Checks.text(address.recipient(),100); Checks.text(address.phone(),32); Checks.text(address.address(),2000);
        if(!address.phone().matches("[+0-9 ()-]{6,32}")) throw new TradeException("INVALID_ARGUMENT","联系电话格式无效");
        return new EncryptedAddress(seal(tenant,orderNo,"recipient",address.recipient()),
            seal(tenant,orderNo,"phone",address.phone()),seal(tenant,orderNo,"address",address.address()),version);
    }
    private byte[] seal(long tenant,String orderNo,String field,String value) {
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            cipher.updateAAD((tenant+":"+orderNo+":"+field).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array();
        } catch(GeneralSecurityException failure) { throw new IllegalStateException("地址加密失败"); }
    }
}
