package com.lrj.trade.refund;

/** 可替换的本地退款政策，不允许在持锁事务内请求外部网络。 */
public interface RefundPolicyPort {
    /** 以同一订单成功加在途加本次退款累计值评估，防拆单绕过大额审批。 */
    Decision evaluate(long tenant,String currency,long cumulativeAmount,boolean abnormal);
    /** 申请冻结策略版本与门槛，配置变化不能把历史待审批自动放行。 */
    record Decision(boolean requiresApproval,long version,long limit) {}
}
