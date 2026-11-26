package com.lrj.trade.core;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;

/** 交易域编号格式。生成时刻与数据库创建时刻使用相同输入，便于零点附近对账。 */
public final class BusinessNumber {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.ofPattern("uuMMdd", Locale.ROOT)
        .withZone(BUSINESS_ZONE);
    private static final long SUFFIX_SPACE = 10_000_000_000L;
    public static final int MAX_ISSUE_ATTEMPTS = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BusinessNumber() {}

    /** 每个前缀由单一业务对象拥有，从已签收的注册表分配。 */
    public enum Type {
        ORDER("ORD"), PAYMENT("PAY"), REFUND("RFD"), FULFILLMENT("FUL"), RETURN_REQUEST("RTN"), RETURN_RECEIPT("RMA"), PAYMENT_RECEIPT("RCT"), MONEY_LEDGER("LED"), RECON_CASE("REC"), RECON_OPERATION("ROP"), QUOTE("QTE"),
        PAYMENT_CALLBACK("PCB"), REFUND_CALLBACK("RCB"), REFUND_APPROVAL("APR");

        private final String prefix;
        private final Pattern pattern;

        Type(String code) {
            this.prefix = "TRD-" + code + "-";
            this.pattern = Pattern.compile(prefix + "[0-9]{6}-[0-9]{10}");
        }
    }

    /** 写库仍以唯一索引裁决并发号碰撞。 */
    public static String next(Type type, Instant createdAt) {
        return type.prefix + BUSINESS_DATE.format(createdAt) + "-"
            + String.format(Locale.ROOT, "%010d", RANDOM.nextLong(SUFFIX_SPACE));
    }

    /** 由数据库报告的确切索引裁决冲突，无需跨租户查询候选号。 */
    public static boolean isNumberCollision(DuplicateKeyException failure, String... indexes) {
        String message=failure.getMostSpecificCause().getMessage();
        if(message==null) return false;
        for(String index:indexes) {
            if(message.endsWith("."+index+"'") || message.endsWith("'"+index+"'")) return true;
        }
        return false;
    }

    /** 跨版本读路径可依靠前缀将新号和旧号引向不同索引。 */
    public static boolean matches(Type type, String value) {
        return value != null && type.pattern.matcher(value).matches();
    }
}
