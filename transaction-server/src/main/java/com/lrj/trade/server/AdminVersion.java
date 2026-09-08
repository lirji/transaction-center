package com.lrj.trade.server;
import com.lrj.trade.core.TradeException;
import java.util.function.Supplier;
/** HTTP强版本条件与业务幂等正文共同校验，不在命令重放前读取当前资源版本。 */
public final class AdminVersion {
    private AdminVersion() {}
    /** 仅接受单个规范强版本标签，不支持通配符、弱标签或候选列表。 */
    public static long parse(String match) {
        if(match==null) throw new TradeException("PRECONDITION_REQUIRED","管理更新必须提供If-Match");
        try {
            if(!match.matches("\"(0|[1-9][0-9]{0,18})\"")) throw new NumberFormatException();
            return Long.parseLong(match.substring(1,match.length()-1));
        } catch(NumberFormatException invalid) { throw new TradeException("INVALID_ARGUMENT","If-Match必须为单个强版本标签"); }
    }
    /** 原正文版本继续参与幂等摘要，服务内过期版本在管理HTTP边界统一为412。 */
    public static <T> T execute(String match,long expected,Supplier<T> operation) {
        long version=parse(match);
        if(expected<0) throw new TradeException("INVALID_ARGUMENT","版本不能为负数");
        if(version!=expected) throw new TradeException("PRECONDITION_FAILED","If-Match与正文版本不一致");
        try { return operation.get(); }
        catch(TradeException error) {
            if(error.code().equals("VERSION_CONFLICT")) throw new TradeException("PRECONDITION_FAILED","资源已更新，请重新读取版本");
            throw error;
        }
    }
}
