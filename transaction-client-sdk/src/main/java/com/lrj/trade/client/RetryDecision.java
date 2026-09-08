package com.lrj.trade.client;

/** 只提供建议，不执行网络请求、不生成新幂等键或推断资金结果。 */
public record RetryDecision(Action action, int delaySeconds) {
    /** 调用者必须保留原交易身份，刷新版本后也须重新核对业务意图。 */
    public enum Action { QUERY_ORIGINAL, REFRESH_VERSION, RETRY_SAME_KEY, STOP }

    /** HTTP与业务信号共同判断；没有可信错误体时不自动重试。 */
    public static RetryDecision from(int httpStatus, TradeFailure failure, boolean hasOriginalKey, String retryAfter) {
        if (failure == null || failure.code() == null) return stop();
        String code = failure.code();
        if (code.equals("PAYMENT_UNRESOLVED") || code.equals("PAYMENT_RESOLUTION_PENDING"))
            return new RetryDecision(Action.QUERY_ORIGINAL, 0);
        if (code.equals("PRECONDITION_FAILED") || code.equals("PRECONDITION_REQUIRED") || code.equals("VERSION_CONFLICT"))
            return new RetryDecision(Action.REFRESH_VERSION, 0);
        // 仅允许已定义的瞬态错误；未知码或幂等冲突不能因标志失配变成自动重试。
        if (!ErrorCodes.RETRYABLE.contains(code) || !failure.retryable() || !hasOriginalKey
                || (httpStatus != 429 && httpStatus != 503)) return stop();
        int delay = delay(retryAfter);
        return delay < 0 ? stop() : new RetryDecision(Action.RETRY_SAME_KEY, delay);
    }

    /** 请求发送后超时无法判断是否受理，先查询原交易，不能更换请求号。 */
    public static RetryDecision transportUncertain() { return new RetryDecision(Action.QUERY_ORIGINAL, 0); }

    private static RetryDecision stop() { return new RetryDecision(Action.STOP, 0); }
    private static int delay(String header) {
        if (header == null) return 1;
        // 未支持的日期、溢出和非法值都不能缩短服务端要求的等待。
        if (!header.matches("[0-9]{1,10}")) return -1;
        try { long seconds = Long.parseLong(header); return seconds > 300 ? -1 : (int)seconds; }
        catch (NumberFormatException ignored) { return -1; }
    }
}
