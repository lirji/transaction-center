package com.lrj.trade.jobs;
import com.lrj.trade.jobs.model.PublicationTask;
import com.lrj.trade.core.query.AdminQuery;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import java.time.Instant;
import com.lrj.trade.core.query.AdminNumberedPage;
import com.lrj.trade.core.*;
import com.lrj.trade.jobs.mapper.JobLeaseMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 只读发布任务队列，隔离坏键也可发现，不能借此重启其他资金任务。 */
@Service
public class OutboxTaskQueryService implements OutboxTaskQueryPort {
    private static final Set<String> QUEUE_STATES = Set.of("READY", "RUNNING", "DONE", "REVIEW");
    private final JobLeaseMapper mapper;
    /** 使用同一快照读取准确条数和页内记录，沿用当前租户与允许的业务筛选。 */
    @Transactional(readOnly = true, timeout = 3, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public AdminNumberedPage<View> numberedPage(long tenant, String state, int limit, long page) {
        Checks.positive(tenant);
        if (state == null || !QUEUE_STATES.contains(state)) throw AdminQuery.invalid();
        return AdminNumberedPage.of(page, limit, mapper.publicationTasksCount(tenant, state), Instant.now(),
                offset -> mapper.publicationTasksAtOffset(tenant, state, limit, offset).stream()
                        .map(OutboxTaskQueryService::safeView).toList());
    }

    /** 查询仍经任务域Mapper，不让后台控制器跨模块读表。 */
    public OutboxTaskQueryService(JobLeaseMapper mapper) { this.mapper=mapper; }
    /** 所有状态显式白名单，游标基于持久主键，不用于消费或历史一致快照。 */
    @Override @Transactional(readOnly=true,timeout=5)
    public List<View> page(long tenant,String state,long after,int limit) {
        Checks.positive(tenant);
        if(state==null || !QUEUE_STATES.contains(state) || after<0 || limit<1 || limit>100)
            throw new TradeException("INVALID_ARGUMENT","发布任务队列参数无效");
        return mapper.publicationTasks(tenant,state,after,limit).stream().map(OutboxTaskQueryService::safeView).toList();
    }

    private static View safeView(PublicationTask r) {
            String event=null;
            try { if(UUID.fromString(r.businessKey()).toString().equals(r.businessKey())) event=r.businessKey(); }
            catch(IllegalArgumentException invalid) { /* 异常键只输出摘要，不展示原文。 */ }
            return new View(r.id(),event,Checks.hash(r.businessKey()),r.status(),r.attempts(),r.nextRunAt(),r.lastError());
    }
}
