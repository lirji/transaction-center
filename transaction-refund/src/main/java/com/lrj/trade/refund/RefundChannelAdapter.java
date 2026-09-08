package com.lrj.trade.refund;

import java.time.Duration;
import com.lrj.trade.payment.PaymentChannelBindingPort;
import com.lrj.trade.refund.model.VerifiedRefundEvidence;

/** 原路退款策略扩展点；仅接入认证过的服务端实现，不能依据用户输入选择任意URL。 */
public interface RefundChannelAdapter {
    /** 固定渠道代码；注册时拒绝重复，不能静默替换原资金路由。 */
    String channelCode();
    /** 默认不允许补发；只有原号永久去重且终态不可复活的供应商合同经认证后才启用。 */
    default boolean supportsSafeCreateReplay() { return false; }
    /**
     * 事务外按计划CREATE或QUERY，必须绑定原商户、扣款号、退款号及金额币种。
     * 实现需设置连接/读取/整体超时并遵守传入预算；租约失效不能取消已抵达渠道的退款。
     * 查不到仅为PENDING或UNKNOWN，不能自动换号、换路或声明不可再成功。
     * 返回证据前必须完成验签或认证查询；原始异常及密钥不得进入日志。
     */
    Reply execute(RefundExecutionPort.Plan plan,PaymentChannelBindingPort.Binding binding,Duration timeout);
    /** 非终态不具备资金裁决权限。 */
    enum State { UNKNOWN, PENDING, EVIDENCE, REPLAYABLE_ABSENCE }
    /** 强类型约束禁止无证据成功，以及在等待状态夹带终态证据。 */
    record Reply(State state,VerifiedRefundEvidence evidence) {
        /** 证据存在性必须与返回类型一致。 */
        public Reply {
            if(state==null || (state==State.EVIDENCE)!=(evidence!=null))
                throw new IllegalArgumentException("退款渠道结果与证据不一致");
        }
        /** 不在默认日志输出资金和签名原文。 */
        @Override public String toString() { return "RefundReply[state="+state+",evidence=REDACTED]"; }
    }
    /** 断连和超时不能证明远端未执行，必须保留同号恢复查询。 */
    final class Uncertain extends RuntimeException {
        /** 使用固定信息，避免供应商异常中的凭据和原文泄露。 */
        public Uncertain() { super("退款渠道调用结果未知"); }
    }
}
