package com.lrj.trade.refund;

/** 已耐久退款证据的租约处理边界，资金效果必须与任务末尾fencing同事务。 */
public interface RefundCallbackPort {
    /** 只处理认证密文与不可变事实；异常资金证据不自动丢弃或释放。 */
    void process(long tenant,String number);
    /** 当前租约持有者才可同事务标为待核对，不覆盖已处理记录。 */
    void markReview(long tenant,String number,String errorCode);
}
