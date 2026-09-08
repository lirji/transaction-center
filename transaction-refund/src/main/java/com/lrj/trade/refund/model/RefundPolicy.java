package com.lrj.trade.refund.model;
import java.time.Instant;

/** 已配置租户免审政策快照；申请保留版本，不追溯改变历史审批路径。 */
public record RefundPolicy(String currency,long autoApproveLimit,long version) {
    /** 日志不默认展开退款标识、原因或身份摘要。 */
    @Override public String toString() { return "RefundPolicy[REDACTED]"; }
}
