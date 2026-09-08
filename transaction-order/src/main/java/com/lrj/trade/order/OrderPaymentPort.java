package com.lrj.trade.order;

import com.lrj.trade.core.Actor;
import com.lrj.trade.order.model.OrderHeader;

/** 支付模块必须经过订单许可，不能越过已取消或过期订单直接发起渠道请求。 */
public interface OrderPaymentPort {
    /** 需要调用方事务；许可与支付意图必须同事务提交，外部调用在提交后。 */
    OrderHeader authorizePayment(Actor actor,String orderNo);
    /** 仅可信支付编排调用，在同一事务中先锁订单再锁支付意图。 */
    OrderHeader lockPaymentOrder(long tenant,long order);
    /** 订单级恢复任务按可信租户和订单号加锁，覆盖没有支付尝试的过期订单。 */
    OrderHeader lockPaymentOrderByNumber(long tenant,String number);
    /** 已验证正常足额收款时原子确认订单、库存和会员首单，不能用于异常多付。 */
    PaymentConfirmation confirmPayment(long tenant,long order,long amount,String currency);
    /** 过期检查只设置关闭中，不以本地时间作为可释放库存证据。 */
    void beginPaymentClose(long tenant,long order);
    /** 调用方须持有所有尝试可靠不可支付证明；仅关闭中订单可释放库存。 */
    void completePaymentClose(long tenant,long order);
    /** 当前写事务的正常支付认定，订单版本与会员事实版本不能混用。 */
    record PaymentConfirmation(String orderId,String memberId,java.time.Instant paidAt,String firstOrderPolicy,
        boolean isFirstConfirmedPaidOrder,long evidenceVersion) {}
}

