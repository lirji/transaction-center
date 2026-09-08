package com.lrj.trade.jobs;

import com.lrj.trade.core.*;
import com.lrj.trade.jobs.model.JobLease;
import com.lrj.trade.refund.RefundRecoveryPort;
import com.lrj.trade.refund.RefundChannelPort;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 退款恢复将本地许可与网络分段；过期工作者不能提交状态，已到达资金证据仍须独立保存。 */
@Service
public class RefundRecoveryWorker {
    private final JobLeaseService jobs;
    private final RefundRecoveryPort refunds;
    private final RefundChannelPort channels;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** 固定公开端口与有界事务，不跨模块访问Mapper或在业务事务内等待网络。 */
    public RefundRecoveryWorker(JobLeaseService jobs,RefundRecoveryPort refunds,RefundChannelPort channels,
        PlatformTransactionManager transactions,Clock clock) {
        this.jobs=jobs; this.refunds=refunds; this.channels=channels; this.clock=clock;
        tx=new TransactionTemplate(transactions); tx.setTimeout(10);
    }
    /** 单任务领取避免预占队列中的大量租约；公平性由有界运行时负责。 */
    @Transactional(propagation=Propagation.NEVER)
    public List<Outcome> runBatch(long tenant,String owner,int limit) {
        Checks.positive(tenant); Checks.text(owner,128);
        if(limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","批次必须为1至100");
        List<Outcome> results=new ArrayList<>();
        for(int i=0;i<limit;i++) {
            var claimed=jobs.claim(tenant,JobType.REFUND_RECOVER,owner,1,30000);
            if(claimed.isEmpty()) break;
            results.add(processLease(claimed.getFirst()));
        }
        return List.copyOf(results);
    }
    /** 原号最多补发一次；QUERY结果后重新读取本地批准和终态，不能复用旧许可。 */
    @Transactional(propagation=Propagation.NEVER)
    public Outcome processLease(JobLease lease) {
        try(var scope=com.lrj.trade.core.TraceContext.open(lease==null?null:lease.traceParent())) {return processLeaseScoped(lease);}
    }
    // 仅包单任务执行，不让租户发现或下一任务继承本次上下文。
    private Outcome processLeaseScoped(JobLease lease) {
        if(lease==null || !JobType.REFUND_RECOVER.name().equals(lease.jobType()))
            throw new TradeException("INVALID_ARGUMENT","退款恢复拒绝其他任务类型");
        try {
            var step=prepare(lease,false);
            if(step.plan().action().equals("DONE")) return Outcome.DONE;
            if(step.plan().action().equals("WAIT_APPROVAL")) return Outcome.RETRY;
            var result=channels.invoke(lease.tenantId(),step.plan());
            if(result==RefundChannelPort.Outcome.REPLAYABLE_ABSENCE) {
                var replay=prepare(lease,true);
                if(replay.plan().action().equals("DONE")) return Outcome.DONE;
                if(replay.plan().action().equals("WAIT_APPROVAL")) return Outcome.RETRY;
                result=channels.invoke(lease.tenantId(),replay.plan());
            }
            String code=switch(result) {
                case UNKNOWN->"REFUND_CHANNEL_UNKNOWN";
                case PENDING->"REFUND_CHANNEL_PENDING";
                case UNAVAILABLE->"REFUND_CHANNEL_UNAVAILABLE";
                case EVIDENCE_ACCEPTED->"REFUND_EVIDENCE_PENDING";
                case REPLAYABLE_ABSENCE->"REFUND_CHANNEL_PROTOCOL_RETRY";
            };
            return retry(lease,code);
        } catch(TransientDataAccessException | CannotCreateTransactionException | TransactionSystemException unavailable) {
            return retry(lease,"DATABASE_RETRY");
        } catch(TradeException error) {
            if(error.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;
            if(Set.of("NOT_FOUND","REFUND_APPROVAL_INVALID","REFUND_BINDING_CONFLICT","STATE_CONFLICT").contains(error.code()))
                return review(lease,"REFUND_RECOVERY_REVIEW");
            if(Set.of("CALLBACK_ENCRYPTION_UNAVAILABLE","PAYMENT_CHANNEL_BINDING_UNAVAILABLE","REFUND_CHANNEL_PROTOCOL_INVALID","REFUND_CHANNEL_BINDING_CONFLICT",
                "REFUND_CALLBACK_FACT_CONFLICT","INVALID_ARGUMENT").contains(error.code()))
                return retry(lease,"REFUND_CHANNEL_EVIDENCE_RETRY");
            throw error;
        }
    }
    private RefundRecoveryPort.Step prepare(JobLease lease,boolean replay) {
        return tx.execute(status->{
            var step=replay?refunds.prepareReplay(lease.tenantId(),lease.businessKey()):
                refunds.prepareRecovery(lease.tenantId(),lease.businessKey());
            String action=step.plan().action();
            if(action.equals("DONE")) jobs.complete(lease);
            else if(action.equals("WAIT_APPROVAL")) {
                long delay=Math.max(100,Math.min(300000,Duration.between(clock.instant(),step.notBefore()).toMillis()));
                jobs.retry(lease,delay,"REFUND_APPROVAL_PENDING");
            } else jobs.fenceAndRenew(lease,30000);
            return step;
        });
    }
    private Outcome retry(JobLease lease,String code) {
        try {
            long cap=Math.min(300000L,5000L << (int)Math.min(6,Math.max(0,lease.attempts()-1)));
            tx.executeWithoutResult(s->jobs.retry(lease,ThreadLocalRandom.current().nextLong(cap/2,cap+1),code));
            return Outcome.RETRY;
        } catch(TradeException stale) {
            if(stale.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;
            throw stale;
        }
    }
    private Outcome review(JobLease lease,String code) {
        try { tx.executeWithoutResult(s->jobs.review(lease,code)); return Outcome.REVIEW; }
        catch(TradeException stale) {
            if(stale.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;
            throw stale;
        }
    }
    /** 调度结果不是渠道资金终态，重试及核对都不能释放预占。 */
    public enum Outcome { DONE, RETRY, REVIEW, LEASE_LOST }
}
