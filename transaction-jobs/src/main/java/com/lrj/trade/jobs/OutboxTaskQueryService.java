package com.lrj.trade.jobs;
import com.lrj.trade.core.*;
import com.lrj.trade.jobs.mapper.JobLeaseMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 只读发布任务队列，隔离坏键也可发现，不能借此重启其他资金任务。 */
@Service
public class OutboxTaskQueryService implements OutboxTaskQueryPort {
    private final JobLeaseMapper mapper;
    /** 查询仍经任务域Mapper，不让后台控制器跨模块读表。 */
    public OutboxTaskQueryService(JobLeaseMapper mapper) { this.mapper=mapper; }
    /** 所有状态显式白名单，游标基于持久主键，不用于消费或历史一致快照。 */
    @Override @Transactional(readOnly=true,timeout=5)
    public List<View> page(long tenant,String state,long after,int limit) {
        Checks.positive(tenant);
        if(state==null || !Set.of("READY","RUNNING","DONE","REVIEW").contains(state) || after<0 || limit<1 || limit>100)
            throw new TradeException("INVALID_ARGUMENT","发布任务队列参数无效");
        return mapper.publicationTasks(tenant,state,after,limit).stream().map(r->{
            String event=null;
            try { if(UUID.fromString(r.businessKey()).toString().equals(r.businessKey())) event=r.businessKey(); }
            catch(IllegalArgumentException invalid) { /* 异常键只输出摘要，不展示原文。 */ }
            return new View(r.id(),event,Checks.hash(r.businessKey()),r.status(),r.attempts(),r.nextRunAt(),r.lastError());
        }).toList();
    }
}
