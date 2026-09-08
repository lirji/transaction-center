package com.lrj.trade.payment;

import com.lrj.trade.payment.model.PaymentDispatch;
import com.lrj.trade.payment.model.VerifiedPaymentCallback;
import java.time.Duration;

/** 渠道策略扩展点；只由经认证的服务端实现，不能从用户报文动态加载。 */
public interface PaymentChannelAdapter {
    /** 唯一渠道代码，启动时拒绝重复实现，防止静默改变资金路由。 */
    String channelCode();

    /** 默认禁止补建；启用需认证原号永久去重、同参数重放及关闭后不可复活合同。 */
    default boolean supportsSafeCreateReplay() { return false; }

    /**
     * 在事务外执行CREATE/QUERY/CLOSE；必须使用原请求号，并设置连接、读取和整体超时。
     * CLOSED只能代表渠道持久不可支付证明，普通200或不存在返回PENDING/UNKNOWN。
     * EVIDENCE必须是已验签或认证查询且绑定原请求的原始证据；禁止记录密钥或原文。
     * 中断/超时后远端可能继续执行，渠道幂等及可靠关闭屏障不能被本地租约替代。
     */
    Reply execute(PaymentDispatch plan,Duration timeout);

    /** 非终态没有资金结论；证据在耐久接收前不能代表本地已经入账。 */
    enum State { UNKNOWN, PENDING, EVIDENCE, REPLAYABLE_ABSENCE }

    /** 类型约束避免把缺少证据的成功布尔值误当收款或可靠关单。 */
    record Reply(State state,VerifiedPaymentCallback evidence) {
        /** 证据只允许出现在明确事实状态，其他状态禁止夹带未经处理的资金结论。 */
        public Reply {
            if(state==null || (state==State.EVIDENCE)!=(evidence!=null))
                throw new IllegalArgumentException("渠道结果与证据不一致");
        }
        /** 默认日志不输出必要原文、商户或交易信息。 */
        @Override public String toString() { return "ChannelReply[state="+state+",evidence=REDACTED]"; }
    }
}
