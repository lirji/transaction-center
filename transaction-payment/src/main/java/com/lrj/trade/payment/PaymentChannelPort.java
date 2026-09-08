package com.lrj.trade.payment;

import com.lrj.trade.payment.model.PaymentDispatch;

/** 任务编排仅依赖公开渠道调用边界，不访问支付实现或Mapper。 */
public interface PaymentChannelPort {
    /** 事务外调用；可信证据已耐久保存才返回EVIDENCE_ACCEPTED，尚不代表业务入账。 */
    Outcome invoke(long tenant,PaymentDispatch plan);

    /** 仅为调度结果；任何枚举都不能直接转换为库存释放或资金失败。 */
    enum Outcome { UNKNOWN, PENDING, EVIDENCE_ACCEPTED, UNAVAILABLE, REPLAYABLE_ABSENCE }
}
