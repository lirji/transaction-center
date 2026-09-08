package com.lrj.trade.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 领域入口校验，内部调用同样不能绕过HTTP校验破坏不变量。 */
public final class Checks {
    private Checks() {}
    /** 拒绝空值和超长输入，数据库不是第一层参数校验。 */
    public static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new TradeException("INVALID_ARGUMENT", "文本为空或超过长度限制");
        }
        return value;
    }
    /** 防止无效租户、主键和数量进入持久层。 */
    public static long positive(long value) {
        if (value <= 0) throw new TradeException("INVALID_ARGUMENT", "数值必须为正");
        return value;
    }
    /** 长度前缀消除分隔符歧义，生成身份和请求的稳定摘要。 */
    public static String hash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

