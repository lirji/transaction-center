package com.lrj.trade.jobs;
import com.lrj.trade.core.OutboxQueueSnapshotPort;
import com.lrj.trade.jobs.mapper.OutboxQueueSnapshotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 固定全局聚合供后台采样线程使用，不在HTTP请求中执行数据库统计。 */
@Service
public class OutboxQueueSnapshotService implements OutboxQueueSnapshotPort {
    private final OutboxQueueSnapshotMapper mapper;
    /** 查询只读取覆盖索引，不锁业务任务或阻止发布。 */
    public OutboxQueueSnapshotService(OutboxQueueSnapshotMapper mapper) { this.mapper=mapper; }
    /** 三秒事务与每状态10001行上限共同限定成本，超过上限只报告下界。 */
    @Override @Transactional(readOnly=true,timeout=3)
    public Snapshot snapshot() {
        var rows=mapper.snapshot();
        if(rows.size()!=3) throw new IllegalStateException("发布队列观测缺少固定状态");
        return new Snapshot(rows.getFirst().observedAt(),rows.stream().map(r->new State(r.status(),r.countLowerBound(),r.oldestAt())).toList());
    }
}
