package com.lrj.trade.server;

import com.lrj.trade.core.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 对已认证且通过专用scope的审批拒绝单独留痕，绝不能记为审批成功或回滚后丢失拒绝记录。 */
@Service
public class RefundApprovalRejectionAudit {
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final Semaphore slots=new Semaphore(4);
    private static final Set<String> CODES=Set.of("REFUND_APPROVAL_UNTRUSTED","REFUND_APPROVAL_EVENT_CONFLICT",
        "REFUND_APPROVAL_CONFLICT","REFUND_APPROVAL_INVALID","NOT_FOUND","INVALID_ARGUMENT","CALLBACK_ENCRYPTION_UNAVAILABLE",
        "CALLBACK_EVIDENCE_INVALID");
    /** 独立三秒事务、四槽位且不排队；这只限制本实例审计并发，不能代替网关总速率限制。 */
    public RefundApprovalRejectionAudit(AuditWriter audit,PlatformTransactionManager transactions,MeterRegistry metrics) {
        this.audit=audit; this.metrics=metrics; tx=new TransactionTemplate(transactions);
        tx.setTimeout(3); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    /** 只存认证Actor哈希、固定拒绝码与请求摘要；失败仍拒绝审批，返回可重试的审计不可用。 */
    public String record(Actor caller,String compact,String code) {
        if(!slots.tryAcquire()) { metric("busy"); throw unavailable(); }
        try {
            String id="ARJ-"+UUID.randomUUID();
            String category=CODES.contains(code)?code:"OTHER_REJECTION";
            String hash=Checks.hash(compact!=null && compact.length()<=16384?compact:"INVALID_SIZE");
            tx.executeWithoutResult(s->audit.append(caller,"refund.approval.rejected-request","APPROVAL_REQUEST",id,category+":"+hash,0,0));
            metric("recorded"); return id;
        } catch(RuntimeException failed) {
            metric("failed"); throw unavailable();
        } finally { slots.release(); }
    }
    private void metric(String outcome) {
        // 指标后端故障不能改变已提交审计的结果，也不能遮蔽原本的失败关闭响应。
        try { metrics.counter("trade.refund.approval.rejection.audit","outcome",outcome).increment(); }
        catch(RuntimeException ignored) { /* 不记录原异常，避免指标实现携带请求或凭据。 */ }
    }
    private static TradeException unavailable() { return new TradeException("REFUND_APPROVAL_AUDIT_UNAVAILABLE","审批仍被拒绝，安全审计暂不可用"); }
}
