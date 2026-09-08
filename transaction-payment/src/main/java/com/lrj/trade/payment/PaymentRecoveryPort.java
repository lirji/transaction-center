package com.lrj.trade.payment;

import com.lrj.trade.payment.model.PaymentDispatch;

/** 恢复任务公开边界；调用许可必须和任务fencing处于同一本地事务。 */
public interface PaymentRecoveryPort {
    /** 只准备本地调用计划，不发网络；调用者必须已有事务并在其内检查租约。 */
    PaymentDispatch prepareInTransaction(long tenant,String requestNo);

    /** 查询确认可按原号安全补建后重检本地屏障；取消或过期时改为关单而非补建。 */
    PaymentDispatch prepareReplayInTransaction(long tenant,String requestNo);
    /** 等待任务fencing锁后再次核对创建许可，不能携带等待期间过期的风险或订单时间。 */
    void assertCreateAllowedInTransaction(long tenant,PaymentDispatch plan);
}
