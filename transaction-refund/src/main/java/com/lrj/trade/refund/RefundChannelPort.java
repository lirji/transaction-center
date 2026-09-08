package com.lrj.trade.refund;

/** 原路退款网络边界；返回调度结果，不允许调用方凭枚举修改资金终态。 */
public interface RefundChannelPort {
    /** 只接受已提交派发屏障的计划；证据持久化成功后才返回接收确认。 */
    Outcome invoke(long tenant,RefundExecutionPort.Plan plan);
    /** 未知、等待和缺配置都必须继续保留预占；接收确认不等于退款业务处理完成。 */
    enum Outcome { UNKNOWN, PENDING, UNAVAILABLE, EVIDENCE_ACCEPTED, REPLAYABLE_ABSENCE }
}
