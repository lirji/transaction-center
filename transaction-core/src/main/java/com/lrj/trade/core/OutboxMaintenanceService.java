package com.lrj.trade.core;
import com.lrj.trade.core.mapper.OutboxMaintenanceMapper;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
/** 分页事务原子维护入队和扫描进度，轮次结束不是全部历史事务已提交的证明。 */
@Service
public class OutboxMaintenanceService implements OutboxMaintenancePort {
    private final OutboxMaintenanceMapper mapper;
    private final JobSchedulerPort jobs;
    private final TransactionTemplate tx;
    /** RC普通读Outbox，控制行锁先于任务，不与发布器逆序锁Outbox。 */
    public OutboxMaintenanceService(OutboxMaintenanceMapper mapper,JobSchedulerPort jobs,PlatformTransactionManager transactions) {
        this.mapper=mapper;this.jobs=jobs;tx=new TransactionTemplate(transactions);tx.setTimeout(5);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 全局游标只为公平轮转，丢失后从0重启不改变租户持久进度。 */
    @Override @Transactional(readOnly=true,timeout=5)
    public OptionalLong nextTenant(long after) {
        if(after<0) throw new TradeException("INVALID_ARGUMENT","租户游标无效");
        var rows=mapper.nextTenant(after);return rows.isEmpty()?OptionalLong.empty():OptionalLong.of(rows.getFirst());
    }
    /** 同租户并行维护者在控制行串行，异常回滚任务和游标，下一次从原页恢复。 */
    @Override @Transactional(propagation=Propagation.NEVER)
    public Step scanPage(long tenant,int limit,long intervalMillis) {
        Checks.positive(tenant);
        if(limit<1 || limit>100 || intervalMillis<60000 || intervalMillis>86400000)
            throw new TradeException("INVALID_ARGUMENT","维护分页或轮次间隔无效");
        return tx.execute(s->{
            mapper.ensure(tenant);var state=mapper.lock(tenant).getFirst();
            if(!state.active() && !state.due()) return new Step(false,0,0,false);
            long cursor=state.cursor(),upper=state.upperId(),scanned=state.scanned(),candidates=state.candidates();
            if(!state.active()) {
                Long current=mapper.upper(tenant);upper=current==null?0:current;cursor=0;scanned=0;candidates=0;
                mapper.start(tenant,upper);
            }
            var page=mapper.page(tenant,cursor,upper,limit);int eligible=0;
            for(var row:page) {
                if(row.status().equals("PENDING") && OutboxEventCatalog.supportedTypes().contains(row.eventType())) {
                    jobs.enqueue(tenant,JobType.OUTBOX_PUBLISH,row.eventId());eligible++;
                }
                cursor=row.id();
            }
            boolean complete=page.size()<limit || cursor>=upper;
            mapper.advance(tenant,cursor,Math.addExact(scanned,page.size()),Math.addExact(candidates,eligible),complete,intervalMillis*1000);
            return new Step(true,page.size(),eligible,complete);
        });
    }
}
