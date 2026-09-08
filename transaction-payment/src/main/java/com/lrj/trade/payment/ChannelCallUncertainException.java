package com.lrj.trade.payment;

/** 适配器超时、断连或无法判定结果时使用；不携带请求原文、凭据或供应商异常消息。 */
public final class ChannelCallUncertainException extends RuntimeException {
    /** 不代表远端失败，调用方必须保留原请求并恢复查询。 */
    public ChannelCallUncertainException() { super("渠道调用结果未知"); }
}
