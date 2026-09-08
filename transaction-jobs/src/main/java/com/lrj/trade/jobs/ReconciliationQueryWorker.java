package com.lrj.trade.jobs;
import com.lrj.trade.core.*;
import com.lrj.trade.jobs.model.JobLease;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
/** 独立只查单通道，不调用普通恢复器或同号补建方法，操作与任务结果同事务fencing。 */
@Service
public class ReconciliationQueryWorker {
    private final JobLeaseService jobs;private final ReconciliationOperationService operations;private final ReconciliationQueryExecutorPort executor;private final TransactionTemplate tx;private final boolean enabled;
    /** 默认关闭，短本地事务只锁操作、差异、任务，不持锁等网络。 */
    public ReconciliationQueryWorker(JobLeaseService jobs,ReconciliationOperationService operations,ReconciliationQueryExecutorPort executor,PlatformTransactionManager transactions,@Value("${trade.reconciliation.query-enabled:false}") boolean enabled) {
        this.jobs=jobs;this.operations=operations;this.executor=executor;this.enabled=enabled;tx=new TransactionTemplate(transactions);tx.setTimeout(5);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 运行时根据开关设零容量，停用时不发现或领取任务。 */
    public boolean enabled() {return enabled;}
    /** 每次领取一条，避免任务在本机网络队列中消耗租约。 */
    @Transactional(propagation=Propagation.NEVER)
    public List<Outcome> runBatch(long tenant,String owner,int limit) {
        Checks.positive(tenant);Checks.text(owner,128);if(limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","查询批次无效");
        if(!enabled) return List.of();var results=new ArrayList<Outcome>();
        for(int i=0;i<limit;i++) {var leases=jobs.claim(tenant,JobType.RECON_QUERY,owner,1,30000);if(leases.isEmpty()) break;results.add(processLease(leases.getFirst()));}
        return List.copyOf(results);
    }
    /** 许可提交后再执行QUERY，远端证据即使失租仍可进入Inbox，本地状态须通过fencing。 */
    @Transactional(propagation=Propagation.NEVER)
    public Outcome processLease(JobLease lease) {
        try(var scope=com.lrj.trade.core.TraceContext.open(lease==null?null:lease.traceParent())) {return processLeaseScoped(lease);}
    }
    // 仅包单任务执行，不让租户发现或下一任务继承本次上下文。
    private Outcome processLeaseScoped(JobLease lease) {
        if(lease==null || !JobType.RECON_QUERY.name().equals(lease.jobType())) throw new TradeException("INVALID_ARGUMENT","仅接收对账查询租约");
        if(!enabled) return Outcome.DISABLED;
        if(!canonical(lease.businessKey())) return reviewInvalidTask(lease);
        ReconciliationOperation operation;
        try {
            operation=tx.execute(s->{
                var op=operations.lock(lease.tenantId(),lease.businessKey());
                if(op==null) throw new TradeException("RECONCILIATION_OPERATION_INVALID","查询操作不存在");
                if(ReconciliationOperationService.terminal(op.status())) {if(op.status().equals("REVIEW")) jobs.review(lease,"QUERY_REVIEW_REQUIRED");else jobs.complete(lease);return null;}
                operations.assertCurrent(lease.tenantId(),op);
                if(lease.attempts()>5) {operations.mark(lease.tenantId(),op,"REVIEW","QUERY_ATTEMPTS_EXHAUSTED");jobs.review(lease,"QUERY_ATTEMPTS_EXHAUSTED");return null;}
                operations.mark(lease.tenantId(),op,"RUNNING","QUERY_PREPARED");jobs.fenceAndRenew(lease,30000);return op;
            });
        } catch(TradeException error) {if(error.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;return finish(lease,true,"QUERY_OPERATION_INVALID",null);}
        if(operation==null) return Outcome.TERMINAL;
        ReconciliationQueryExecutorPort.Outcome result;
        try {result=executor.execute(lease.tenantId(),operation.channelId(),operation.action(),operation.referenceType(),operation.businessRef());}
        catch(TradeException invalid) {return finish(lease,true,"QUERY_PLAN_REJECTED",null);}
        catch(RuntimeException uncertain) {return finish(lease,false,"QUERY_CALL_UNCERTAIN",null);}
        if(result==null) return finish(lease,true,"QUERY_PROTOCOL_INVALID",null);
        return switch(result) {
            case EVIDENCE_ACCEPTED -> finish(lease,false,"QUERY_EVIDENCE_ACCEPTED",result);
            case REPLAYABLE_ABSENCE -> finish(lease,true,"QUERY_ABSENCE_REVIEW",result);
            case UNKNOWN,PENDING,UNAVAILABLE -> finish(lease,false,"QUERY_"+result.name(),result);
        };
    }
    private Outcome finish(JobLease lease,boolean review,String code,ReconciliationQueryExecutorPort.Outcome result) {
        try {return tx.execute(s->{
            var op=operations.lock(lease.tenantId(),lease.businessKey());
            if(op==null) {jobs.review(lease,"QUERY_OPERATION_MISSING");return Outcome.REVIEW;}
            if(ReconciliationOperationService.terminal(op.status())) {if(op.status().equals("REVIEW")) jobs.review(lease,"QUERY_REVIEW_REQUIRED");else jobs.complete(lease);return Outcome.TERMINAL;}
            if(result==ReconciliationQueryExecutorPort.Outcome.EVIDENCE_ACCEPTED) {
                operations.assertCurrent(lease.tenantId(),op);operations.mark(lease.tenantId(),op,"RESULT_ACCEPTED",code);jobs.complete(lease);return Outcome.RESULT_ACCEPTED;
            }
            if(review || lease.attempts()>=5) {operations.mark(lease.tenantId(),op,"REVIEW",code);jobs.review(lease,code);return Outcome.REVIEW;}
            operations.mark(lease.tenantId(),op,"QUEUED",code);jobs.retry(lease,Math.min(30000,1000L << (int)Math.min(5,lease.attempts())),code);return Outcome.RETRY;
        });} catch(TradeException lost) {if(lost.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;throw lost;}
    }
    private Outcome reviewInvalidTask(JobLease lease) {
        try {tx.executeWithoutResult(s->jobs.review(lease,"QUERY_INVALID_OPERATION_KEY"));return Outcome.REVIEW;}
        catch(TradeException lost) {if(lost.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;throw lost;}
    }
    private static boolean canonical(String value) {try {return value!=null && value.length()==36 && UUID.fromString(value).toString().equals(value);} catch(IllegalArgumentException invalid) {return false;}}
    /** 查询操作终态与差异结案分开；失租不表示远端查询未发生。 */
    public enum Outcome {DISABLED,RETRY,REVIEW,RESULT_ACCEPTED,TERMINAL,LEASE_LOST}
}
