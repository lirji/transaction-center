package com.lrj.trade.core;
/** 只读核对原渠道归属，不读取凭据或触发资金状态推进。 */
public interface ReconciliationOwnershipPort {
    /** 引用可为空；非空使用原支付请求号、收款编号或资金退款编号。 */
    void require(long tenant,long channel,String type,String reference);
}
