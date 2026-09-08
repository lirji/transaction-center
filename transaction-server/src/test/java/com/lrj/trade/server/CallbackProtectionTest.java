package com.lrj.trade.server;

import com.lrj.trade.core.TradeException;
import com.lrj.trade.payment.AesCallbackPayloadProtector;
import com.lrj.trade.payment.model.EncryptedCallback;
import com.lrj.trade.payment.model.VerifiedPaymentCallback;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 回调密文认证、历史密钥轮换和对象脱敏测试，随机密钥只在测试进程内使用。 */
class CallbackProtectionTest {
    @Test void unavailableOrInvalidKeyNeverAcceptsPlaintext() {
        var disabled=new AesCallbackPayloadProtector("","");
        assertEquals("CALLBACK_ENCRYPTION_UNAVAILABLE",assertThrows(TradeException.class,()->disabled.encrypt("binding",new byte[]{1})).code());
        assertThrows(IllegalArgumentException.class,()->new AesCallbackPayloadProtector("v1","v1:invalid"));
        assertThrows(IllegalArgumentException.class,()->new AesCallbackPayloadProtector("v1","v1:"+key()+",v1:"+key()));
        assertThrows(IllegalArgumentException.class,()->new AesCallbackPayloadProtector("missing","v1:"+key()));
    }

    @Test void authenticatedCipherRejectsTamperingAndUsesFreshNonce() {
        var protector=new AesCallbackPayloadProtector("v1","v1:"+key());
        byte[] raw="必要原始回调".getBytes(StandardCharsets.UTF_8);
        var first=protector.encrypt("binding",raw);
        assertFalse(Arrays.equals(first.ciphertext(),protector.encrypt("binding",raw).ciphertext()));
        assertArrayEquals(raw,protector.decrypt("binding",first));
        assertEquals("CALLBACK_EVIDENCE_INVALID",assertThrows(TradeException.class,()->protector.decrypt("other-binding",first)).code());
        byte[] corrupt=first.ciphertext(); corrupt[corrupt.length-1]^=1;
        assertEquals("CALLBACK_EVIDENCE_INVALID",assertThrows(TradeException.class,()->protector.decrypt("binding",new EncryptedCallback("v1",corrupt))).code());
        assertArrayEquals(raw,protector.decrypt("binding",first));
        assertThrows(TradeException.class,()->protector.encrypt("binding",new byte[65537]));
    }

    @Test void keyRotationKeepsOldEvidenceReadableWithoutChangingVersion() {
        String one=key(),two=key();
        var old=new AesCallbackPayloadProtector("v1","v1:"+one);
        byte[] raw={1,2,3};
        var envelope=old.encrypt("binding",raw);
        var rotated=new AesCallbackPayloadProtector("v2","v1:"+one+",v2:"+two);
        assertArrayEquals(raw,rotated.decrypt("binding",envelope));
        assertEquals("v2",rotated.encrypt("binding",raw).keyVersion());
        var retired=new AesCallbackPayloadProtector("v2","v2:"+two);
        assertEquals("CALLBACK_ENCRYPTION_UNAVAILABLE",assertThrows(TradeException.class,()->retired.decrypt("binding",envelope)).code());
        assertEquals("CALLBACK_EVIDENCE_INVALID",assertThrows(TradeException.class,()->rotated.decrypt("binding",new EncryptedCallback("v2",envelope.ciphertext()))).code());
    }

    @Test void payloadObjectsDefensivelyCopyAndRedactTheirContents() {
        byte[] raw="sensitive-payload".getBytes(StandardCharsets.UTF_8);
        var callback=new VerifiedPaymentCallback("channel","merchant","event","request","CLOSED",null,null,null,null,"cert",raw);
        raw[0]=0;
        assertNotEquals(0,callback.payload()[0]);
        byte[] copy=callback.payload(); copy[0]=0;
        assertNotEquals(0,callback.payload()[0]);
        assertFalse(callback.toString().contains("sensitive"));
        assertFalse(new EncryptedCallback("v1",raw).toString().contains("sensitive"));
    }

    private static String key() { return Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32)); }
}
