package com.lrj.trade.core.query;
import com.lrj.trade.core.TradeException;
/** 数据库事实超出JSON安全整数边界时拒绝展示，不进行浮点截断。 */
public final class AdminNumbers {
    private AdminNumbers() {}
    /** 适用于金额、数量、版本和次数；异常数据不是客户端参数错误。 */
    public static void require(long value) {
        if(value<0 || value>9007199254740991L) throw new TradeException("ADMIN_QUERY_UNAVAILABLE","读取数值超出安全范围");
    }
}
