package com.lrj.trade.refund;
import com.lrj.trade.core.Actor;
import com.lrj.trade.refund.model.RefundView;

/** 仅供验真后的流程结果调用，普通会员或未验签报文不能获得此内部能力。 */
public interface RefundApprovalPort {
    /** 外层事务内校验关联、审批期限、版本及职责分离；外部适配器仍必须先认证审批主体。 */
    RefundView applyApproval(Actor approver,String refundNo,String approvalRef,long decisionVersion,boolean approve,String evidenceRef);
    /** 在认证受理事务内关联该次审批尝试。 */
    RefundView applyApproval(Actor approver,String refundNo,String approvalRef,long decisionVersion,boolean approve,
                             String evidenceRef,Long auditAttempt);
}
