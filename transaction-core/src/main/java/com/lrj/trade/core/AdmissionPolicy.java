package com.lrj.trade.core;
/** 租户新增交易策略；缺省策略版本零允许受理，既有业务恢复不受影响。 */
public record AdmissionPolicy(boolean orderEnabled,boolean paymentEnabled,long version) {
    /** 暂停仅拒绝新增订单，不丢失已有幂等命令响应。 */
    public void requireOrder() {if(!orderEnabled) throw new TradeException("ORDER_ADMISSION_PAUSED","新订单受理已暂停，请保留原幂等键");}
    /** 只在确实需要新增支付意图或尝试之前调用。 */
    public void requirePayment() {if(!paymentEnabled) throw new TradeException("PAYMENT_ADMISSION_PAUSED","新支付受理已暂停，请保留原幂等键");}
}
