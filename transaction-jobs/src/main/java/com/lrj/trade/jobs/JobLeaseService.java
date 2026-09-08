package com.lrj.trade.jobs;

import com.lrj.trade.core.*;
import com.lrj.trade.jobs.mapper.JobLeaseMapper;
import com.lrj.trade.jobs.model.JobLease;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 数据库租约协议；不执行网络，令牌失败通过调用方事务回滚防止旧工作者提交业务。 */
@Service
public class JobLeaseService implements JobSchedulerPort,OutboxReplaySchedulePort {
    private final JobLeaseMapper mapper;
    private final TransactionTemplate tx;

    /** 领取及续租使用独立短事务，调用返回前已提交，不把行锁带到渠道网络。 */
    public JobLeaseService(JobLeaseMapper mapper, PlatformTransactionManager transactions) {
        this.mapper=mapper;
        this.tx=new TransactionTemplate(transactions);
        this.tx.setTimeout(10);
        // 队列不需要历史快照；短事务用读已提交减少到期索引更新的间隙锁竞争。
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 受控重放保留全局fencing/attempts，不能重置旧工作者的隔离屏障。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public long requeueReviewed(long tenant,String eventId) {
        Checks.positive(tenant); Checks.text(eventId,36);
        var rows=mapper.lockReviewedPublication(tenant,eventId);
        if(rows.size()!=1) throw new TradeException("STATE_CONFLICT","发布任务不在隔离状态");
        var previous=rows.getFirst();
        if(mapper.requeuePublication(tenant,eventId)!=1) throw new TradeException("STATE_CONFLICT","发布任务状态已变化");
        return previous.attempts();
    }
    /** 必须与领域事实同事务，重复入队不擦除尝试次数、错误或终态。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(long tenant, JobType type, String businessKey) {
        Checks.positive(tenant); requireType(type); Checks.text(businessKey,128);
        mapper.insertJob(tenant,type.name(),businessKey,TraceContext.currentParent());
    }

    /** 延迟预约与业务同事务，重放不覆盖首次调度；需要提前时显式wake。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void schedule(long tenant,JobType type,String businessKey,java.time.Instant runAt) {
        Checks.positive(tenant); requireType(type); Checks.text(businessKey,128);
        if(runAt==null || runAt.isBefore(java.time.Instant.parse("1000-01-01T00:00:00Z"))
            || !runAt.isBefore(java.time.Instant.parse("+10000-01-01T00:00:00Z")))
            throw new TradeException("INVALID_ARGUMENT","预约时间超出数据库范围");
        mapper.schedule(tenant,type.name(),businessKey,runAt,TraceContext.currentParent());
    }

    /** 缺任务先补齐；已有终态保持不变，运行中唤醒由代数保护不丢失。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void wake(long tenant,JobType type,String businessKey) {
        Checks.positive(tenant); requireType(type); Checks.text(businessKey,128);
        mapper.insertJob(tenant,type.name(),businessKey,TraceContext.currentParent());
        mapper.wake(tenant,type.name(),businessKey);
    }

    /** 只领取当前租户到期任务；批次和租约有上限，防止单工作者占满队列。 */
    @Transactional(propagation=Propagation.NEVER)
    public List<JobLease> claim(long tenant, JobType type, String owner, int limit, long leaseMillis) {
        Checks.positive(tenant); requireType(type); Checks.text(owner,128); requireLease(leaseMillis);
        if(limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","任务批次必须在1到100之间");
        return tx.execute(status->{
            List<JobLease> leases=new ArrayList<>();
            for(long id:mapper.claimable(tenant,type.name(),limit)) {
                requireChanged(mapper.claim(tenant,id,owner,leaseMillis*1000));
                leases.add(mapper.read(tenant,id).getFirst());
            }
            return List.copyOf(leases);
        });
    }

    /** 到期后即使尚无接管者也不允许续活，必须重新领取并取得更高令牌。 */
    @Transactional(propagation=Propagation.NEVER)
    public JobLease renew(JobLease lease, long leaseMillis) {
        long id=validate(lease); requireLease(leaseMillis);
        return tx.execute(status->{
            mapper.lock(lease.tenantId(),id);
            requireChanged(mapper.renew(lease.tenantId(),id,lease.owner(),lease.fencingToken(),leaseMillis*1000,lease.jobType(),lease.businessKey()));
            return mapper.read(lease.tenantId(),id).getFirst();
        });
    }

    /** 本地调用计划之后同事务校验并续租；失效时回滚许可，不能把此令牌当渠道端屏障。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void fenceAndRenew(JobLease lease,long leaseMillis) {
        long id=validate(lease); requireLease(leaseMillis);
        mapper.lock(lease.tenantId(),id);
        requireChanged(mapper.renew(lease.tenantId(),id,lease.owner(),lease.fencingToken(),leaseMillis*1000,lease.jobType(),lease.businessKey()));
    }

    /** 在业务副作用之后、同事务内完成；失效时必须回滚业务，不可独立提交。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void complete(JobLease lease) {
        finish(lease,"DONE",0,null);
    }

    /** 未决或可重试错误只延迟调度，不释放资金或库存；错误仅允许分类码。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void retry(JobLease lease, long delayMillis, String errorCode) {
        if(delayMillis<100 || delayMillis>86400000)
            throw new TradeException("INVALID_ARGUMENT","任务退避必须在100毫秒到一天之间");
        requireError(errorCode);
        finish(lease,"READY",delayMillis,errorCode);
    }

    /** 永久无法自动处理时保留任务与错误分类，后续运营流程不得据此判定资金失败。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void review(JobLease lease, String errorCode) {
        requireError(errorCode);
        finish(lease,"REVIEW",0,errorCode);
    }

    private void finish(JobLease lease, String state, long delayMillis, String error) {
        long id=validate(lease);
        // 先等行锁，再以新的数据库语句时间判断租约；失败会标记整个调用方事务回滚。
        mapper.lock(lease.tenantId(),id);
        requireChanged(mapper.finish(lease.tenantId(),id,lease.owner(),lease.fencingToken(),state,delayMillis*1000,error,lease.jobType(),lease.businessKey()));
    }

    private static long validate(JobLease lease) {
        if(lease==null) throw new TradeException("INVALID_ARGUMENT","租约不能为空");
        Checks.positive(lease.tenantId()); Checks.positive(lease.fencingToken()); Checks.text(lease.owner(),128);
        Checks.text(lease.jobType(),64); Checks.text(lease.businessKey(),128);
        try { return Checks.positive(Long.parseLong(lease.jobId())); }
        catch(NumberFormatException error) { throw new TradeException("INVALID_ARGUMENT","任务标识无效"); }
    }

    private static void requireType(JobType type) {
        if(type==null) throw new TradeException("INVALID_ARGUMENT","任务类型不能为空");
    }

    private static void requireLease(long millis) {
        if(millis<1000 || millis>300000) throw new TradeException("INVALID_ARGUMENT","租约必须在1秒到5分钟之间");
    }

    private static void requireError(String error) {
        if(error==null || !error.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new TradeException("INVALID_ARGUMENT","只允许脱敏错误分类码");
    }

    private static void requireChanged(int rows) {
        if(rows!=1) throw new TradeException("JOB_LEASE_LOST","任务租约已失效或被接管");
    }
}
