package com.lrj.trade.jobs;

import com.lrj.trade.core.*;
import com.lrj.trade.jobs.model.JobLease;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 先提交发送计划、事务外网络、ACK后原子完成；跨提交不确定窗口允许永久事件键重放。 */
@Service
public class OutboxPublishWorker {
    private final JobLeaseService jobs;
    private final OutboxDeliveryPort outbox;
    private final EventPublisherPort publisher;
    private final TransactionTemplate tx;
    /** 30秒租约预留20秒以内网络及本地提交余量，锁顺序固定Outbox到任务。 */
    public OutboxPublishWorker(JobLeaseService jobs,OutboxDeliveryPort outbox,EventPublisherPort publisher,PlatformTransactionManager transactions) {
        this.jobs=jobs;this.outbox=outbox;this.publisher=publisher;tx=new TransactionTemplate(transactions);tx.setTimeout(5);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 关闭时连领取都不执行，不能将配置关闭误记为发布失败。 */
    @Transactional(propagation=Propagation.NEVER)
    public List<Outcome> runBatch(long tenant,String owner,int limit) {
        Checks.positive(tenant);Checks.text(owner,128);
        if(limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","发布批次必须为1至100");
        if(!publisher.enabled()) return List.of();
        var result=new ArrayList<Outcome>();
        for(int i=0;i<limit;i++) {
            var claimed=jobs.claim(tenant,JobType.OUTBOX_PUBLISH,owner,1,30000);
            if(claimed.isEmpty()) break;
            result.add(processLease(claimed.getFirst()));
        }
        return List.copyOf(result);
    }
    /** 旧租约可造成Broker重复接收，但不能提交本地SENT或覆盖新持有者状态。 */
    @Transactional(propagation=Propagation.NEVER)
    public Outcome processLease(JobLease lease) {
        try(var scope=com.lrj.trade.core.TraceContext.open(lease==null?null:lease.traceParent())) {return processLeaseScoped(lease);}
    }
    // 仅包单任务执行，不让租户发现或下一任务继承本次上下文。
    private Outcome processLeaseScoped(JobLease lease) {
        if(lease==null || !JobType.OUTBOX_PUBLISH.name().equals(lease.jobType()))
            throw new TradeException("INVALID_ARGUMENT","发布工作者拒绝其他类型租约");
        if(!publisher.enabled()) return Outcome.DISABLED;
        if(!validEventKey(lease.businessKey())) return failure(lease,true,"OUTBOX_INVALID_EVENT_KEY");
        OutboxEventCatalog.Message message;
        try {
            message=tx.execute(s->{
                var row=outbox.lock(lease.tenantId(),lease.businessKey());
                if(row==null) throw new InvalidEvent();
                if(row.status().equals("SENT")) { jobs.complete(lease);return null; }
                if(lease.attempts()<=row.attemptBase()) throw new TradeException("JOB_LEASE_LOST","旧重放轮次已失效");
                if(!row.status().equals("PENDING") || lease.attempts()-row.attemptBase()>10) throw new InvalidEvent();
                OutboxEventCatalog.Message validated;
                try { validated=OutboxEventCatalog.validate(row.tenant(),row.eventId(),row.eventType(),row.aggregateId(),row.payload()); }
                catch(RuntimeException invalid) { throw new InvalidEvent(); }
                jobs.fenceAndRenew(lease,30000);return validated;
            });
        } catch(InvalidEvent invalid) { return failure(lease,true,"OUTBOX_INVALID_OR_EXHAUSTED"); }
          catch(TradeException lost) { if(lost.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;throw lost; }
        if(message==null) return Outcome.DONE;
        EventPublisherPort.Receipt receipt;
        try {
            receipt=publisher.publish(message);
            if(receipt==null || !message.eventId().equals(receipt.eventId())) return failure(lease,true,"OUTBOX_ACK_MISMATCH");
        } catch(RuntimeException uncertain) { return failure(lease,false,"OUTBOX_SEND_UNCERTAIN"); }
        try {
            tx.executeWithoutResult(s->{
                var row=outbox.lock(lease.tenantId(),lease.businessKey());
                if(row==null) throw new TradeException("OUTBOX_STATE_CONFLICT","发布事件丢失");
                if(lease.attempts()<=row.attemptBase()) throw new TradeException("JOB_LEASE_LOST","旧重放轮次已失效");
                if(!row.status().equals("SENT")) outbox.sent(lease.tenantId(),lease.businessKey(),receipt);
                jobs.complete(lease);
            });
            return Outcome.DONE;
        } catch(TradeException lost) {
            if(lost.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;
            throw lost;
        }
        // ACK后的数据库故障直接保留租约等待接管，不额外提交失败或伪造发送未发生。
    }
    private Outcome failure(JobLease lease,boolean review,String code) {
        try {
            return tx.execute(s->{
                var row=validEventKey(lease.businessKey())?outbox.lock(lease.tenantId(),lease.businessKey()):null;
                if(row!=null && row.status().equals("SENT")) { jobs.complete(lease);return Outcome.DONE; }
                long attempt=row==null?lease.attempts():lease.attempts()-row.attemptBase();
                if(attempt<=0) throw new TradeException("JOB_LEASE_LOST","旧重放轮次已失效");
                boolean isolate=review || attempt>=10;
                if(row!=null && OutboxEventCatalog.supportedTypes().contains(row.eventType()))
                    outbox.failed(lease.tenantId(),lease.businessKey(),code,isolate);
                if(isolate) jobs.review(lease,code);
                else jobs.retry(lease,Math.min(60000,1000L << Math.min(6,attempt-1)),code);
                return isolate?Outcome.REVIEW:Outcome.RETRY;
            });
        } catch(TradeException lost) { if(lost.code().equals("JOB_LEASE_LOST")) return Outcome.LEASE_LOST;throw lost; }
    }
    private static boolean validEventKey(String key) {
        try { return key!=null && UUID.fromString(key).toString().equals(key); }
        catch(IllegalArgumentException invalid) { return false; }
    }
    /** DONE仅表示Broker确认已被本地原子记录，消费者处理仍须自己的去重事务。 */
    public enum Outcome { DONE,RETRY,REVIEW,LEASE_LOST,DISABLED }
    /** 仅作为内部控制信号，禁止携带原始载荷。 */
    private static final class InvalidEvent extends RuntimeException {}
}
