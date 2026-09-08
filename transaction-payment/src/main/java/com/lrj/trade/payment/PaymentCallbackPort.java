package com.lrj.trade.payment;

/** 后台执行器只调用业务端口，不读取支付表或绕开资金事务。 */
public interface PaymentCallbackPort {
    /** 必须参加外层租约事务，成功处理与资金副作用及任务完成一起提交。 */
    void process(long tenant, String callbackNo);
    /** 确定异常进入核对；必须与当前租约的REVIEW提交同事务，不能清除资金或库存。 */
    void markReview(long tenant, String callbackNo, String errorCode);
}
