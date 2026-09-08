package com.lrj.trade.refund;

import com.lrj.trade.core.*;
import com.lrj.trade.refund.mapper.ApprovalReceiptMapper;
import com.lrj.trade.refund.model.VerifiedApprovalReceipt;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** 验真后的回执留证与审批原子提交；返回编号才表示受理，不创建绕过认证的HTTP入口。 */
@Service
public class RefundApprovalReceiptService {
    private static final String NAMESPACE="refund-approval-v1";
    private final ApprovalReceiptMapper mapper;
    private final RefundApprovalPort approvals;
    private final EvidenceProtectionPort protector;
    private final Clock clock;
    private final TransactionTemplate tx;
    /** 复用公开审批端口与认证加密；独立短事务包含证据、审计和持久唤醒。 */
    public RefundApprovalReceiptService(ApprovalReceiptMapper mapper,RefundApprovalPort approvals,EvidenceProtectionPort protector,
        Clock clock,PlatformTransactionManager transactions) {
        this.mapper=mapper; this.approvals=approvals; this.protector=protector; this.clock=clock;
        tx=new TransactionTemplate(transactions); tx.setTimeout(10);
    }
    /** 只能接收可信认证适配层产物；网络验签和加密在事务外，资金许可仍在退款域重检。 */
    @Transactional(propagation=Propagation.NEVER)
    public String accept(VerifiedApprovalReceipt receipt) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("审批受理禁止外层事务");
        validate(receipt);
        long tenant=receipt.approver().tenantId();
        String number="RAP-"+UUID.randomUUID();
        String facts=Checks.hash(receipt.sourceIssuer(),receipt.sourceSubject(),receipt.eventId(),tenant,receipt.approver().key(),
            receipt.refundNo(),receipt.approvalRef(),receipt.decisionVersion(),receipt.approve(),receipt.evidenceRef());
        String hash=Checks.hash(receipt.signedReceipt());
        byte[] raw=receipt.signedReceipt().getBytes(StandardCharsets.UTF_8);
        EvidenceProtectionPort.Envelope encrypted;
        try { encrypted=protector.encrypt(NAMESPACE,binding(tenant,number,facts,hash),raw); }
        finally { Arrays.fill(raw,(byte)0); }
        return tx.execute(s->{
            mapper.insert(tenant,number,receipt.sourceIssuer(),receipt.sourceSubject(),receipt.eventId(),receipt.refundNo(),facts,hash,
                encrypted.ciphertext(),encrypted.keyVersion());
            var stored=mapper.lockEvent(tenant,receipt.sourceIssuer(),receipt.sourceSubject(),receipt.eventId()).stream().findFirst()
                .orElseThrow(()->new TradeException("REFUND_APPROVAL_EVENT_CONFLICT","审批来源事件所属租户冲突"));
            if(!stored.factHash().equals(facts) || !stored.payloadHash().equals(hash) || !stored.refundNo().equals(receipt.refundNo()))
                throw new TradeException("REFUND_APPROVAL_EVENT_CONFLICT","审批来源事件的原文或语义改变");
            byte[] proof=protector.decrypt(NAMESPACE,binding(tenant,stored.receiptNo(),facts,hash),
                new EvidenceProtectionPort.Envelope(stored.keyVersion(),stored.cipher()));
            try {
                if(!Checks.hash(new String(proof,StandardCharsets.UTF_8)).equals(hash))
                    throw new TradeException("REFUND_APPROVAL_EVENT_CONFLICT","审批证据摘要异常");
            } finally { Arrays.fill(proof,(byte)0); }
            // 重复成功事件可直接返回；首个受理在等待锁后再次检查回执时效，再由领域校验审批业务期限。
            if(!stored.receiptNo().equals(number)) return stored.receiptNo();
            if(!clock.instant().isBefore(receipt.expiresAt())) throw new TradeException("REFUND_APPROVAL_UNTRUSTED","审批回执已过期");
            approvals.applyApproval(receipt.approver(),receipt.refundNo(),receipt.approvalRef(),receipt.decisionVersion(),receipt.approve(),receipt.evidenceRef());
            // 领域锁等待可能跨越JWT截止点，末尾重检失败必须连同审批与唤醒一起回滚。
            if(!clock.instant().isBefore(receipt.expiresAt())) throw new TradeException("REFUND_APPROVAL_UNTRUSTED","审批回执已过期");
            return stored.receiptNo();
        });
    }
    private static String binding(long tenant,String number,String facts,String hash) { return Checks.hash(NAMESPACE,tenant,number,facts,hash); }
    private static void validate(VerifiedApprovalReceipt r) {
        if(r==null || r.approver()==null || r.issuedAt()==null || r.expiresAt()==null || !r.expiresAt().isAfter(r.issuedAt()))
            throw new TradeException("INVALID_ARGUMENT","审批回执不完整");
        Checks.text(r.sourceIssuer(),255); Checks.text(r.sourceSubject(),255); Checks.text(r.eventId(),64);
        Checks.text(r.refundNo(),64); Checks.text(r.approvalRef(),64); Checks.text(r.evidenceRef(),255);
        Checks.positive(r.decisionVersion()); Checks.text(r.signedReceipt(),16384);
    }
}
