package com.lrj.trade.server;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.refund.RefundApprovalReceiptService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 审批入口只接受紧凑签名原文，固定验真后交给原子受理，绝不反序列化可信内部记录。 */
@Service
public class RefundApprovalIngress {
    private final RefundApprovalReceiptService receipts;
    private final ObjectProvider<RefundApprovalVerifier> verifiers;
    private final RefundApprovalRejectionAudit rejections;
    /** 缺验证器意味着未启用，不能使用恒通过的替身或明文降级。 */
    public RefundApprovalIngress(RefundApprovalReceiptService receipts,ObjectProvider<RefundApprovalVerifier> verifiers,RefundApprovalRejectionAudit rejections) {
        this.receipts=receipts; this.verifiers=verifiers;
        this.rejections=rejections;
    }
    /** HTTP层先验证专用scope；这里只使用已认证Actor，失败不保存或日志输出签名报文。 */
    public String accept(Actor caller,String compact) {
        var verifier=verifiers.getIfAvailable();
        if(verifier==null) throw new TradeException("REFUND_APPROVAL_DISABLED","可信审批接收尚未启用");
        try { return receipts.accept(verifier.verify(caller,compact)); }
        catch(TradeException refused) {
            String auditId=rejections.record(caller,compact,refused.code());
            throw new ApprovalRejection(refused.code(),refused.getMessage(),auditId);
        }
    }
}
