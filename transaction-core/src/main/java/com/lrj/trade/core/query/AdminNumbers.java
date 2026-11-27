package com.lrj.trade.core.query;
import com.lrj.trade.core.TradeException;
/** 数据库事实超出JSON安全整数边界时拒绝展示，不进行浮点截断。 */
public final class AdminNumbers {
    /** JSON 安全整数的上界，同时用于页码类请求参数。 */
    public static final long MAX_SAFE_INTEGER = 9007199254740991L;

    private AdminNumbers() {}
    /** 适用于金额、数量、版本和次数；异常数据不是客户端参数错误。 */
    public static void require(long value) {
        if(value<0 || value>MAX_SAFE_INTEGER) throw new TradeException("ADMIN_QUERY_UNAVAILABLE","读取数值超出安全范围");
    }
}
