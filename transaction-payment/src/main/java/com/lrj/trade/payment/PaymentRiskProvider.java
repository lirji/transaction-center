package com.lrj.trade.payment;

import com.lrj.trade.payment.model.RiskEvaluation;
import java.time.Duration;

/** 供应商适配策略；实际身份/字段映射与超时遵守外部认证合同，不接受用户指定地址。 */
public interface PaymentRiskProvider {
    /** 必须在完整超时预算内结束；网络异常不能转换成ALLOW。 */
    PaymentRiskPort.Decision evaluate(RiskEvaluation evaluation,Duration timeout);
}
