package com.lrj.trade.refund;
/** 对账只读退款查单，不能派发未审批或从未提交的申请。 */
public interface RefundQueryPlanPort {
    /** 申请与资金原绑定必须一致，只允许已存在提交事实的QUERY。 */
    RefundExecutionPort.Plan query(long tenant,long channel,String refundNo);
}
