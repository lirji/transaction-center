package com.lrj.trade.payment;

import com.lrj.trade.payment.model.RiskEvaluation;
import java.time.Instant;

/** 支付风险公开边界；未知不放行，风险结论不代表真实资金状态。 */
public interface PaymentRiskPort {
    /** 已有事务内锁订单再准备不可变评估，不能在这里发网络。 */
    RiskEvaluation prepareInTransaction(long tenant,String requestNo);
    /** 事务外调用受信任固定策略，缺能力返回WAIT，不提供默认放行。 */
    Decision evaluate(RiskEvaluation evaluation);
    /** 已有事务内保存绑定校验后的结果；调用Worker还必须在同事务末尾检查fencing。 */
    void acceptInTransaction(RiskEvaluation evaluation,Decision decision);

    /** 精确白名单动作，未知供应商动作必须映射为WAIT或拒绝解析。 */
    enum Action { WAIT, ALLOW, DENY }
    /** 可信适配器回显绑定，禁止把另一租户或旧轮次结果装配到当前支付。 */
    record Decision(String evaluationNo,long tenantId,String requestNo,String inputHash,Action action,
        String decisionId,String ruleVersion,String modelVersion,Instant validUntil) {
        /** 结果中的风险元数据不能被默认日志输出。 */
        @Override public String toString() { return "RiskDecision[REDACTED]"; }
    }
}
