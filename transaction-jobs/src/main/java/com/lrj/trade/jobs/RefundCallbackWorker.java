package com.lrj.trade.jobs;

import com.lrj.trade.core.*;
import com.lrj.trade.jobs.model.JobLease;
import com.lrj.trade.refund.RefundCallbackPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 只执行已耐久接收的退款事实，不发网络、不伪造验签；任务完成与业务副作用同事务。 */
@Service
public class RefundCallbackWorker {
    private final JobLeaseService jobs;
    private final RefundCallbackPort callbacks;
    private final TransactionTemplate tx;

    /** 每个任务短事务处理，失败分类只保存固定错误码，不泄露渠道报文。 */
    public RefundCallbackWorker(JobLeaseService jobs,RefundCallbackPort callbacks,PlatformTransactionManager transactions) {
        this.jobs=jobs; this.callbacks=callbacks;
        this.tx=new TransactionTemplate(transactions); this.tx.setTimeout(10);
    }

    /** 逐个领取避免预占大批租约后尚未处理就过期；调用方负责租户公平调度与停机。 */
    @Transactional(propagation=Propagation.NEVER)
    public List<Outcome> runBatch(long tenant,String owner,int limit) {
        Checks.positive(tenant); Checks.text(owner,128);
        if(limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","批次必须在1到100之间");
        List<Outcome> results=new ArrayList<>();
        for(int i=0;i<limit;i++) {
            var claimed=jobs.claim(tenant,JobType.REFUND_CALLBACK,owner,1,30000);
            if(claimed.isEmpty()) break;
            results.add(processLease(claimed.getFirst()));
        }
        return List.copyOf(results);
    }

    /** 使用领取时的不可变凭据，过期或替换业务引用会使整笔业务事务回滚。 */
    @Transactional(propagation=Propagation.NEVER)
    public Outcome processLease(JobLease lease) {
        try(var scope=com.lrj.trade.core.TraceContext.open(lease==null?null:lease.traceParent())) {return processLeaseScoped(lease);}
    }
    // 仅包单任务执行，不让租户发现或下一任务继承本次上下文。
    private Outcome processLeaseScoped(JobLease lease) {
        if(lease==null || !JobType.REFUND_CALLBACK.name().equals(lease.jobType()))
            throw new TradeException("INVALID_ARGUMENT","回调工作者拒绝其他任务类型");
        try {
            tx.executeWithoutResult(status->{
                callbacks.process(lease.tenantId(),lease.businessKey());
                jobs.complete(lease);
            });
            return Outcome.DONE;
        } catch(TransientDataAccessException unavailable) {
            return failure(lease,false,"DATABASE_RETRY");
        } catch(org.springframework.transaction.CannotCreateTransactionException
                | org.springframework.transaction.TransactionSystemException unavailable) {
            // 提交应答丢失可能实际已提交；只重调同一业务任务，终态或失效租约不被重置。
            return failure(lease,false,"TRANSACTION_RETRY");
        } catch(TradeException error) {
            if(error.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;
            if(error.code().equals("CALLBACK_ENCRYPTION_UNAVAILABLE"))
                return failure(lease,false,"CALLBACK_KEY_UNAVAILABLE");
            if(Set.of("CALLBACK_EVIDENCE_INVALID","PAYMENT_FACT_CONFLICT","REFUND_FACT_CONFLICT","REFUND_TERMINAL_CONFLICT","REFUND_BINDING_CONFLICT",
                "REFUND_CALLBACK_BINDING_CONFLICT","STATE_CONFLICT","NOT_FOUND").contains(error.code()))
                return failure(lease,true,"CALLBACK_REVIEW_REQUIRED");
            throw error;
        }
    }

    private Outcome failure(JobLease lease,boolean review,String code) {
        try {
            tx.executeWithoutResult(status->{
                if(review) {
                    callbacks.markReview(lease.tenantId(),lease.businessKey(),code);
                    jobs.review(lease,code);
                } else {
                    // 指数退避封顶五分钟并加抖动；未知资金不因尝试次数多而自动判失败。
                    long cap=Math.min(300000L,5000L << (int)Math.min(6,Math.max(0,lease.attempts()-1)));
                    jobs.retry(lease,ThreadLocalRandom.current().nextLong(cap/2,cap+1),code);
                }
            });
            return review?Outcome.REVIEW:Outcome.RETRY;
        } catch(TradeException stale) {
            if(stale.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;
            throw stale;
        }
    }

    /** 执行结果不是退款状态；REVIEW或RETRY均不能用于释放库存或退款额度。 */
    public enum Outcome { DONE, RETRY, REVIEW, LEASE_LOST }
}
