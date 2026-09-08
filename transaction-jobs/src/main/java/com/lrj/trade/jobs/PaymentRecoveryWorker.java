package com.lrj.trade.jobs;

import com.lrj.trade.core.*;
import com.lrj.trade.jobs.model.JobLease;
import com.lrj.trade.payment.PaymentRecoveryPort;
import com.lrj.trade.payment.PaymentChannelPort;
import com.lrj.trade.payment.PaymentRiskPort;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 支付恢复编排：本地许可与fencing提交后发网络，证据耐久接收与任务退避分段。 */
@Service
public class PaymentRecoveryWorker {
    private final JobLeaseService jobs;
    private final PaymentRecoveryPort payments;
    private final PaymentChannelPort channels;
    private final PaymentRiskPort risks;
    private final TransactionTemplate tx;

    /** 所有本地事务有界；不在事务回调内执行渠道或等待网络。 */
    public PaymentRecoveryWorker(JobLeaseService jobs,PaymentRecoveryPort payments,PaymentChannelPort channels,
        PlatformTransactionManager transactions,PaymentRiskPort risks) {
        this.jobs=jobs; this.payments=payments; this.channels=channels;
        this.risks=risks;
        this.tx=new TransactionTemplate(transactions); this.tx.setTimeout(10);
    }

    /** 调用方负责租户公平性和持续调度；逐个领取，禁止大量任务等待网络时提前占租约。 */
    @Transactional(propagation=Propagation.NEVER)
    public List<Outcome> runBatch(long tenant,String owner,int limit) {
        Checks.positive(tenant); Checks.text(owner,128);
        if(limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","批次必须在1到100之间");
        List<Outcome> results=new ArrayList<>();
        for(int i=0;i<limit;i++) {
            var leases=jobs.claim(tenant,JobType.PAYMENT_RECOVER,owner,1,30000);
            if(leases.isEmpty()) break;
            results.add(processLease(leases.getFirst()));
        }
        return List.copyOf(results);
    }

    /** 即使网络执行后租约失效，可信证据也先独立保存；失效工作者仅不能覆盖任务状态。 */
    @Transactional(propagation=Propagation.NEVER)
    public Outcome processLease(JobLease lease) {
        try(var scope=com.lrj.trade.core.TraceContext.open(lease==null?null:lease.traceParent())) {return processLeaseScoped(lease);}
    }
    // 仅包单任务执行，不让租户发现或下一任务继承本次上下文。
    private Outcome processLeaseScoped(JobLease lease) {
        if(lease==null || !JobType.PAYMENT_RECOVER.name().equals(lease.jobType()))
            throw new TradeException("INVALID_ARGUMENT","支付恢复工作者拒绝其他任务类型");
        try {
            var plan=tx.execute(status->{
                var prepared=payments.prepareInTransaction(lease.tenantId(),lease.businessKey());
                if("DONE".equals(prepared.action())) jobs.complete(lease);
                else if("REVIEW".equals(prepared.action())) jobs.review(lease,"PAYMENT_REVIEW_REQUIRED");
                else jobs.fenceAndRenew(lease,30000);
                payments.assertCreateAllowedInTransaction(lease.tenantId(),prepared);
                return prepared;
            });
            if("DONE".equals(plan.action())) return Outcome.DONE;
            if("REVIEW".equals(plan.action())) return Outcome.REVIEW;
            if("RISK".equals(plan.action())) return evaluateRisk(lease);
            // 许可事务已提交；网络期间不持数据库锁，原请求号不会随重试生成。
            var result=channels.invoke(lease.tenantId(),plan);
            if(result==PaymentChannelPort.Outcome.REPLAYABLE_ABSENCE) {
                // 每次租约最多一次同号补建；网络查询之后重新锁订单，不能使用取消前的旧计划。
                var replay=tx.execute(status->{
                    var prepared=payments.prepareReplayInTransaction(lease.tenantId(),lease.businessKey());
                    if("DONE".equals(prepared.action())) jobs.complete(lease);
                    else if("REVIEW".equals(prepared.action())) jobs.review(lease,"PAYMENT_REVIEW_REQUIRED");
                    else jobs.fenceAndRenew(lease,30000);
                    payments.assertCreateAllowedInTransaction(lease.tenantId(),prepared);
                    return prepared;
                });
                if("DONE".equals(replay.action())) return Outcome.DONE;
                if("REVIEW".equals(replay.action())) return Outcome.REVIEW;
                if("RISK".equals(replay.action())) return evaluateRisk(lease);
                result=channels.invoke(lease.tenantId(),replay);
            }
            String code=switch(result) {
                case UNKNOWN -> "CHANNEL_UNKNOWN";
                case PENDING -> "CHANNEL_PENDING";
                case EVIDENCE_ACCEPTED -> "EVIDENCE_PENDING";
                case UNAVAILABLE -> "CHANNEL_UNAVAILABLE";
                case REPLAYABLE_ABSENCE -> "CHANNEL_PROTOCOL_RETRY";
            };
            // 接收已提交并非业务已处理，仍保留恢复任务直到下一次看到可靠本地终态。
            return retry(lease,code);
        } catch(TransientDataAccessException | CannotCreateTransactionException | TransactionSystemException error) {
            return retry(lease,"DATABASE_RETRY");
        } catch(TradeException error) {
            if("JOB_LEASE_LOST".equals(error.code())) return Outcome.LEASE_LOST;
            if(Set.of("CALLBACK_ENCRYPTION_UNAVAILABLE","CHANNEL_PROTOCOL_INVALID","CHANNEL_BINDING_CONFLICT",
                "CALLBACK_BINDING_CONFLICT","CALLBACK_FACT_CONFLICT","INVALID_ARGUMENT",
                "RISK_BINDING_CONFLICT","RISK_DECISION_CONFLICT","RISK_EXPIRED","RISK_PROTOCOL_INVALID",
                "PAYMENT_PERMISSION_CHANGED").contains(error.code()))
                return retry(lease,"CHANNEL_EVIDENCE_RETRY");
            throw error;
        }
    }

    private Outcome evaluateRisk(JobLease lease) {
        var evaluation=tx.execute(status->{
            var prepared=risks.prepareInTransaction(lease.tenantId(),lease.businessKey());
            jobs.fenceAndRenew(lease,30000);
            return prepared;
        });
        if(!evaluation.state().equals("PENDING")) return retry(lease,"RISK_RECHECK",100);
        var decision=risks.evaluate(evaluation);
        tx.executeWithoutResult(status->{
            risks.acceptInTransaction(evaluation,decision);
            jobs.fenceAndRenew(lease,30000);
        });
        // 风控成功也不直接复用旧CREATE计划；下一批重新检查取消、到期、风险有效期与租约。
        // 已确认结果使用短重检，不能沿用高attempts指数退避把60秒ALLOW永远等到过期。
        return decision.action()==PaymentRiskPort.Action.WAIT?retry(lease,"RISK_WAIT"):retry(lease,"RISK_RECHECK",100);
    }

    private Outcome retry(JobLease lease,String code) {
        long cap=Math.min(300000L,5000L << (int)Math.min(6,Math.max(0,lease.attempts()-1)));
        return retry(lease,code,ThreadLocalRandom.current().nextLong(cap/2,cap+1));
    }

    private Outcome retry(JobLease lease,String code,long delay) {
        try {
            tx.executeWithoutResult(status->jobs.retry(lease,delay,code));
            return Outcome.RETRY;
        } catch(TradeException stale) {
            if("JOB_LEASE_LOST".equals(stale.code())) return Outcome.LEASE_LOST;
            throw stale;
        }
    }

    /** 任务结果不是渠道资金状态；重试和待核对不触发库存释放。 */
    public enum Outcome { DONE, RETRY, REVIEW, LEASE_LOST }
}
