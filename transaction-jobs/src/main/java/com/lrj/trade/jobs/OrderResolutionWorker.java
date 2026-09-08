package com.lrj.trade.jobs;

import com.lrj.trade.core.*;
import com.lrj.trade.jobs.model.JobLease;
import com.lrj.trade.payment.OrderResolutionPort;
import com.lrj.trade.payment.PaymentChannelPort;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 订单级关闭任务不依赖旧支付任务是否DONE；网络关闭证据仍先独立耐久接收。 */
@Service
public class OrderResolutionWorker {
    private final JobLeaseService jobs;
    private final OrderResolutionPort orders;
    private final PaymentChannelPort channels;
    private final TransactionTemplate tx;

    /** 任务业务处理十秒事务，网络只在提交后的阶段执行。 */
    public OrderResolutionWorker(JobLeaseService jobs,OrderResolutionPort orders,PaymentChannelPort channels,PlatformTransactionManager transactions) {
        this.jobs=jobs; this.orders=orders; this.channels=channels;
        this.tx=new TransactionTemplate(transactions); this.tx.setTimeout(10);
    }

    /** 受控批次逐个领取，租户公平调度及常驻运行由后续执行器负责。 */
    @Transactional(propagation=Propagation.NEVER)
    public List<Outcome> runBatch(long tenant,String owner,int limit) {
        Checks.positive(tenant); Checks.text(owner,128);
        if(limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","批次必须在1到100之间");
        List<Outcome> result=new ArrayList<>();
        for(int i=0;i<limit;i++) {
            var leases=jobs.claim(tenant,JobType.ORDER_RESOLVE,owner,1,30000);
            if(leases.isEmpty()) break;
            result.add(processLease(leases.getFirst()));
        }
        return List.copyOf(result);
    }

    /** 订单状态与完成租约原子提交；处理中取消产生的新唤醒不能被旧退避覆盖。 */
    @Transactional(propagation=Propagation.NEVER)
    public Outcome processLease(JobLease lease) {
        try(var scope=com.lrj.trade.core.TraceContext.open(lease==null?null:lease.traceParent())) {return processLeaseScoped(lease);}
    }
    // 仅包单任务执行，不让租户发现或下一任务继承本次上下文。
    private Outcome processLeaseScoped(JobLease lease) {
        if(lease==null || !JobType.ORDER_RESOLVE.name().equals(lease.jobType()))
            throw new TradeException("INVALID_ARGUMENT","订单解析工作者拒绝其他任务类型");
        try {
            var plan=tx.execute(status->{
                var prepared=orders.prepare(lease.tenantId(),lease.businessKey());
                switch(prepared.state()) {
                    case DONE -> jobs.complete(lease);
                    case REVIEW -> jobs.review(lease,"ORDER_REVIEW_REQUIRED");
                    case WAIT -> jobs.retry(lease,prepared.delayMillis(),"ORDER_NOT_DUE");
                    case NETWORK -> jobs.fenceAndRenew(lease,30000);
                }
                return prepared;
            });
            switch(plan.state()) {
                case DONE: return Outcome.DONE;
                case REVIEW: return Outcome.REVIEW;
                case WAIT: return Outcome.RETRY;
                case NETWORK:
                    channels.invoke(lease.tenantId(),plan.dispatch());
                    // 接收成功仅意味证据已入库，必须等回调处理后的本地订单终态再完成任务。
                    return retry(lease,"ORDER_CLOSE_PENDING");
                default: throw new IllegalStateException("订单解析状态无效");
            }
        } catch(TransientDataAccessException | CannotCreateTransactionException | TransactionSystemException unavailable) {
            return retry(lease,"DATABASE_RETRY");
        } catch(TradeException error) {
            if("JOB_LEASE_LOST".equals(error.code())) return Outcome.LEASE_LOST;
            if(Set.of("CALLBACK_ENCRYPTION_UNAVAILABLE","CHANNEL_PROTOCOL_INVALID","CHANNEL_BINDING_CONFLICT",
                "CALLBACK_BINDING_CONFLICT","CALLBACK_FACT_CONFLICT","INVALID_ARGUMENT").contains(error.code()))
                return retry(lease,"ORDER_EVIDENCE_RETRY");
            throw error;
        }
    }

    private Outcome retry(JobLease lease,String code) {
        try {
            long cap=Math.min(300000L,5000L << (int)Math.min(6,Math.max(0,lease.attempts()-1)));
            tx.executeWithoutResult(status->jobs.retry(lease,ThreadLocalRandom.current().nextLong(cap/2,cap+1),code));
            return Outcome.RETRY;
        } catch(TradeException stale) {
            if("JOB_LEASE_LOST".equals(stale.code())) return Outcome.LEASE_LOST;
            throw stale;
        }
    }

    /** 只是本地任务结果，REVIEW不等于可靠不可支付证明。 */
    public enum Outcome { DONE, RETRY, REVIEW, LEASE_LOST }
}
