package com.lrj.trade.core;

/** 业务拒绝与技术故障分离，避免客户端把库存不足当作可盲重试故障。 */
public final class TradeException extends RuntimeException {
    private final String code;
    /** 保留稳定错误码，响应层不暴露数据库异常。 */
    public TradeException(String code, String message) { super(message); this.code = code; }
    /** 返回供合同和客户端判断的稳定业务码。 */
    public String code() { return code; }
}

