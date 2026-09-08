package com.lrj.trade.jobs;

import com.lrj.trade.core.JobType;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.jobs.mapper.JobLeaseMapper;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 后台受信任的全租户发现，不向用户开放，也不访问其他领域的Mapper。 */
@Service
public class JobTenantDiscovery {
    private final JobLeaseMapper mapper;
    private final TransactionTemplate tx;

    /** 每次发现独立短事务，返回前释放连接，不把发现连接带到下一任务事务。 */
    public JobTenantDiscovery(JobLeaseMapper mapper,PlatformTransactionManager transactions) {
        this.mapper=mapper; this.tx=new TransactionTemplate(transactions);
        this.tx.setTimeout(10); this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 至多返回一个可运行任务租户，未来到期任务也可发现；实际领取仍检查主库时间。 */
    @Transactional(propagation=Propagation.NEVER)
    public OptionalLong next(JobType type,long after) {
        if(type==null || after<0) throw new TradeException("INVALID_ARGUMENT","任务类型或租户游标无效");
        return tx.execute(status->{
            var rows=mapper.nextTenant(type.name(),after);
            return rows.isEmpty()?OptionalLong.empty():OptionalLong.of(rows.getFirst());
        });
    }
}
