package com.lrj.trade.server;

import com.lrj.trade.order.AesAddressProtector;
import com.lrj.trade.order.model.ShippingAddress;
import com.lrj.trade.core.TradeException;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 地址密文的隔离安全验证，不使用生产密钥或共享数据库。 */
class AddressProtectionTest {
    @Test void absentOrInvalidKeyNeverFallsBackToPlaintext() {
        var disabled=new AesAddressProtector("","");
        assertEquals("ADDRESS_ENCRYPTION_UNAVAILABLE",assertThrows(TradeException.class,
            ()->disabled.encrypt(1,"order",new ShippingAddress("收件人","13800138000","地址"))).code());
        assertThrows(IllegalArgumentException.class,()->new AesAddressProtector("invalid","v1"));
        assertThrows(IllegalArgumentException.class,()->new AesAddressProtector(Base64.getEncoder().encodeToString(new byte[16]),"v1"));
    }
    @Test void nonceIsFreshAndCipherIsBoundToTenantOrderAndField() throws Exception {
        byte[] key=new SecureRandom().generateSeed(32);
        var protector=new AesAddressProtector(Base64.getEncoder().encodeToString(key),"test");
        var address=new ShippingAddress("收件人","13800138000","测试地址");
        byte[] first=protector.encrypt(1,"one",address).recipient();
        byte[] second=protector.encrypt(1,"one",address).recipient();
        assertFalse(Arrays.equals(first,second));
        for(String wrong:List.of("2:one:recipient","1:two:recipient","1:one:phone")) {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(first,12)));
            cipher.updateAAD(wrong.getBytes(StandardCharsets.UTF_8));
            assertThrows(javax.crypto.AEADBadTagException.class,()->cipher.doFinal(Arrays.copyOfRange(first,12,first.length)));
        }
        assertFalse(address.toString().contains("13800138000"));
    }
}
