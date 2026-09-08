package com.lrj.trade.payment;

import com.lrj.trade.payment.model.PaymentDispatch;

/** 订单生命周期恢复边界，覆盖无支付尝试及旧支付任务已完成后的取消。 */
public interface OrderResolutionPort {
    /** 必须加入任务业务事务，调用方在同事务执行fencing后才可调用网络。 */
    Plan prepare(long tenant,String orderNo);

    /** 解析结果不以尝试关闭代替订单终态；WAIT只延迟订单复核。 */
    enum State { DONE, WAIT, NETWORK, REVIEW }

    /** 网络计划只在NETWORK状态存在，其余状态不得夹带渠道执行许可。 */
    record Plan(State state,long delayMillis,PaymentDispatch dispatch) {
        /** 非网络结果无渠道许可，避免工作者错用旧请求发起调用。 */
        public Plan {
            if(state==null || (state==State.NETWORK)!=(dispatch!=null))
                throw new IllegalArgumentException("订单解析结果不一致");
        }
    }
}
