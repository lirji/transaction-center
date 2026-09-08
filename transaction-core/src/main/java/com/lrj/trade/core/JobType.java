package com.lrj.trade.core;

/** 白名单任务类型，新增类型必须同时扩展处理器与数据库约束，不能执行任意命令。 */
public enum JobType {
    PAYMENT_RECOVER,
    PAYMENT_CALLBACK,
    ORDER_RESOLVE,
    REFUND_CALLBACK,
    REFUND_RECOVER,
    REFUND_COMPENSATE,
    OUTBOX_PUBLISH,
    RECON_QUERY
}
