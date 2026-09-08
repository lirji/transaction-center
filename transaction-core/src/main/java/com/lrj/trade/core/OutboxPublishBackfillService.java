package com.lrj.trade.core;

import com.lrj.trade.core.mapper.OutboxDeliveryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
/** 旧写入者和在途事务退出后从零补漏，不将历史v0或人工隔离记录重新发布。 */
@Service
public class OutboxPublishBackfillService {
    private final OutboxDeliveryMapper mapper;
    private final JobSchedulerPort jobs;
    private final TransactionTemplate tx;
    /** 每页RC短事务；任务唯一键保证重放不复活DONE/REVIEW。 */
    public OutboxPublishBackfillService(OutboxDeliveryMapper mapper,JobSchedulerPort jobs,PlatformTransactionManager transactions) {
        this.mapper=mapper; this.jobs=jobs;tx=new TransactionTemplate(transactions);tx.setTimeout(10);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 返回实际扫描末尾ID，包括不发布的v0，避免无界扫描找下一条V1。 */
    @Transactional(propagation=Propagation.NEVER)
    public long enqueuePage(long tenant,long after,int limit) {
        Checks.positive(tenant);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","补漏游标或批次无效");
        return tx.execute(s->{
            long last=after;
            for(var row:mapper.page(tenant,after,limit)) {
                if(row.status().equals("PENDING") && OutboxEventCatalog.supportedTypes().contains(row.eventType()))
                    jobs.enqueue(tenant,JobType.OUTBOX_PUBLISH,row.eventId());
                last=row.id();
            }
            return last;
        });
    }
}
