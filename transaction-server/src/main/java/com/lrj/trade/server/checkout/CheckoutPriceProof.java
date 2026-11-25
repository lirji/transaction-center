package com.lrj.trade.server.checkout;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 交易只认营销双方约定的结算签价协议及固定信任公钥。 */
final class CheckoutPriceProof {
    static final int MAX_PROOF_SIZE = 24_000;
    static final String PURPOSE = "marketing.checkout-price/v1";
    private static final Set<String> FIELDS = Set.of("purpose", "marketingTenantId", "tradeTenantId", "tradeQuoteRef",
        "scopeVersion", "sourceDigest", "organizationId", "shopId", "ownerRef", "cartDigest", "marketingQuoteId",
        "campaignId", "generation", "bundleId", "bundleVersion", "eligibleSkuId", "offerId", "benefitVersion",
        "currency", "grossMinor", "discountMinor", "payableMinor", "funderId", "issuedAt", "expiresAt", "lines");
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private CheckoutPriceProof() { }

    /** 解析已配置的 X.509 Ed25519 公钥；无有效配置时禁止开启冻结。 */
    static PublicKey publicKey(String encoded) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("试点签价公钥无效", invalid);
        }
    }

    /** 验签后才解析负载，结算协议只接收已定义的 ASCII 字段和有序长度编码。 */
    static Map<String, String> verify(String token, String keyId, PublicKey key) {
        return verify(token, keyId, key, PURPOSE, FIELDS);
    }

    /** 同一编码可承载不同用途，验签方必须明确使用对应字段集合和信任密钥。 */
    static Map<String, String> verify(String token, String keyId, PublicKey key, String purpose, Set<String> allowedFields) {
        try {
            if (token.length() > MAX_PROOF_SIZE) throw invalid();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !keyId.equals(ascii(decodeSegment(parts[0])))) throw invalid();
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(decodeSegment(parts[2]))) throw invalid();
            var fields = decode(ascii(decodeSegment(parts[1])), allowedFields);
            if (!fields.keySet().equals(allowedFields) || !purpose.equals(fields.get("purpose"))) throw invalid();
            return fields;
        } catch (GeneralSecurityException failure) {
            throw invalid();
        }
    }

    private static byte[] decodeSegment(String segment) {
        byte[] bytes = URL_DECODER.decode(segment);
        if (!URL_ENCODER.encodeToString(bytes).equals(segment)) throw invalid();
        return bytes;
    }

    private static Map<String, String> decode(String serialized, Set<String> allowedFields) {
        Map<String, String> result = new LinkedHashMap<>();
        int cursor = 0;
        String previous = "";
        while (cursor < serialized.length()) {
            int separator = serialized.indexOf(':', cursor);
            int keyEnd = end(serialized, cursor, separator);
            String key = serialized.substring(separator + 1, keyEnd);
            int valueSeparator = serialized.indexOf(':', keyEnd);
            int valueEnd = end(serialized, keyEnd, valueSeparator);
            if (key.compareTo(previous) <= 0 || !allowedFields.contains(key)) throw invalid();
            result.put(key, serialized.substring(valueSeparator + 1, valueEnd));
            previous = key;
            cursor = valueEnd;
        }
        return Map.copyOf(result);
    }

    private static int end(String data, int cursor, int separator) {
        if (separator <= cursor || separator - cursor > 5) throw invalid();
        String length = data.substring(cursor, separator);
        if (!length.matches("0|[1-9][0-9]{0,4}")) throw invalid();
        int end = separator + 1 + Integer.parseInt(length);
        if (end > data.length()) throw invalid();
        return end;
    }

    private static String ascii(byte[] bytes) {
        for (byte value : bytes) if (value < 0) throw invalid();
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /** 原报价摘要和冻结凭证摘要都按 UTF-8 原文计算。 */
    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("缺少交易摘要算法", impossible);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("试点结算签名或格式无效");
    }
}
