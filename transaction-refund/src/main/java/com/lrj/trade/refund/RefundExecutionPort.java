package com.lrj.trade.refund;
import java.time.Instant;
import com.lrj.trade.refund.model.RefundView;

/** 退款Worker的事务内编排合同；必须末尾fencing提交后才允许事务外渠道调用。 */
public interface RefundExecutionPort {
    /** 就绪申请写入派发屏障；已派发只查询原号，待审批不会放行。 */
    Plan prepare(long tenant,String refundNo);
    /** 已耐久接收且验真的成功结果，与应用、组件、资金、订单证据和fencing同事务。 */
    RefundView confirmSucceeded(long tenant,String refundNo,String providerRefundNo,long amount,String currency,Instant occurredAt,String evidenceRef);
    /** 只有原渠道不可再成功的认证终态才释放，UNKNOWN没有此权限。 */
    RefundView confirmFailed(long tenant,String refundNo,String evidenceRef);
    /** 审批超时且从未派发才可释放；必须由持租约的本地恢复流程同事务调用。 */
    RefundView expireApproval(long tenant,String refundNo);
    /** 固定请求和原扣款绑定；不携带密钥，不提供绕过事务的网络执行方法。 */
    record Plan(long tenantId,String refundNo,String merchantRefundNo,String receiptNo,String paymentAttemptId,
        String channelCode,String channelMerchantNo,String providerTransactionNo,long amount,String currency,String action) {
        /** 不默认记录资金标识或渠道商户。 */
        @Override public String toString() { return "RefundPlan[REDACTED]"; }
    }
}
