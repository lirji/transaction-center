package com.lrj.trade.jobs;

import com.lrj.trade.core.*;
import com.lrj.trade.jobs.model.JobLease;
import com.lrj.trade.refund.RefundCompensationPort;
import java.util.*;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 异常收款任务只建立唯一待审批补偿，不绕过人工决定或直接调用渠道。 */
@Service
public class RefundCompensationWorker {
    private final JobLeaseService jobs;
    private final RefundCompensationPort refunds;
    private final TransactionTemplate tx;
    /** 本地补偿与任务末尾fencing共用十秒事务；无网络或等待人工审批。 */
    public RefundCompensationWorker(JobLeaseService jobs,RefundCompensationPort refunds,PlatformTransactionManager transactions) {
        this.jobs=jobs; this.refunds=refunds; tx=new TransactionTemplate(transactions); tx.setTimeout(10);
    }
    /** 固定批次逐个领取，避免一个实例占有大量尚未处理的租约。 */
    @Transactional(propagation=Propagation.NEVER)
    public List<Outcome> runBatch(long tenant,String owner,int limit) {
        Checks.positive(tenant); Checks.text(owner,128);
        if(limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","补偿批次必须为1至100");
        var outcomes=new ArrayList<Outcome>();
        for(int i=0;i<limit;i++) {
            var claimed=jobs.claim(tenant,JobType.REFUND_COMPENSATE,owner,1,30000);
            if(claimed.isEmpty()) break;
            outcomes.add(processLease(claimed.getFirst()));
        }
        return List.copyOf(outcomes);
    }
    /** 旧租约不能提交申请；确定冲突转人工核对，数据库瞬态错误保留原号重试。 */
    @Transactional(propagation=Propagation.NEVER)
    public Outcome processLease(JobLease lease) {
        try(var scope=com.lrj.trade.core.TraceContext.open(lease==null?null:lease.traceParent())) {return processLeaseScoped(lease);}
    }
    // 仅包单任务执行，不让租户发现或下一任务继承本次上下文。
    private Outcome processLeaseScoped(JobLease lease) {
        if(lease==null || !JobType.REFUND_COMPENSATE.name().equals(lease.jobType()))
            throw new TradeException("INVALID_ARGUMENT","补偿工作者拒绝其他任务类型");
        try {
            return tx.execute(status->{ refunds.requestCompensation(lease.tenantId(),lease.businessKey()); jobs.complete(lease); return Outcome.DONE; });
        } catch(TransientDataAccessException | CannotCreateTransactionException | TransactionSystemException failure) {
            return finishFailure(lease,false);
        } catch(TradeException failure) {
            if(failure.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;
            if(Set.of("REFUND_COMPENSATION_INVALID","REFUND_COMPENSATION_CONFLICT","NOT_FOUND","STATE_CONFLICT","REFUND_BINDING_CONFLICT").contains(failure.code()))
                return finishFailure(lease,true);
            throw failure;
        }
    }
    private Outcome finishFailure(JobLease lease,boolean review) {
        try {
            tx.executeWithoutResult(status->{
                if(review) jobs.review(lease,"REFUND_COMPENSATION_REVIEW");
                else jobs.retry(lease,5000,"DATABASE_RETRY");
            });
            return review?Outcome.REVIEW:Outcome.RETRY;
        } catch(TradeException stale) {
            if(stale.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;
            throw stale;
        }
    }
    /** DONE仅表示补偿申请已建立或原申请已存在，不代表已经批准或退款成功。 */
    public enum Outcome { DONE,RETRY,REVIEW,LEASE_LOST }
}
