package com.lrj.trade.refund;

import java.time.Instant;

/** 主动退款恢复内部边界；本地许可必须和末尾任务fencing一起提交。 */
public interface RefundRecoveryPort {
    /** 处理审批到期、准备初次派发或原号查询，不执行网络。 */
    Step prepareRecovery(long tenant,String refundNo);
    /** 仅经认证的安全同号补发结论可调用；重读本地终态后决定是否仍可派发。 */
    Step prepareReplay(long tenant,String refundNo);
    /** 待审批时间只供调度使用，不提供客户端可篡改的派发许可。 */
    record Step(RefundExecutionPort.Plan plan,Instant notBefore) {
        /** 避免资金路由出现在自动日志中。 */
        @Override public String toString() { return "RefundRecoveryStep[REDACTED]"; }
    }
}
