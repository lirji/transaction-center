package com.lrj.trade.core;
import java.time.Instant;
import java.util.List;
/** 固定发布队列的内部聚合观测，不提供租户或任务明细。 */
public interface OutboxQueueSnapshotPort {
    /** 一条SQL读取三个状态的有界计数及最老时间；计数上限10001表示下界。 */
    Snapshot snapshot();
    /** 最老创建时间为持久任务时间，不是采样进程启动时间。 */
    record State(String status,long countLowerBound,Instant oldestAt) {}
    /** observedAt来自同一条数据库语句，各实例不得把相同全局计数相加。 */
    record Snapshot(Instant observedAt,List<State> states) {
        /** 快照对监控线程不可变，不能在读Gauge时触发数据库查询。 */
        public Snapshot { states=List.copyOf(states); }
    }
}
