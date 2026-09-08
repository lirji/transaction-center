package com.lrj.trade.refund;

import com.lrj.trade.refund.model.RefundView;

/** 后台仅按已持久真实收款创建补偿，不能直接批准或改渠道资金终态。 */
public interface RefundCompensationPort {
    /** 加入调用方本地事务，租约失效时整个申请、资金及审批任务一起回滚。 */
    RefundView requestCompensation(long tenant,String receiptNo);
}
