package com.lrj.trade.jobs.mapper;

import com.lrj.trade.jobs.model.JobLease;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 任务表独立持久化边界，所有租约时间在数据库计算，不信任工作进程时钟。 */
@Mapper
public interface JobLeaseMapper {
    /** 固定发布类型的租户内队列，包括坏事件键任务。 */
    List<com.lrj.trade.jobs.model.PublicationTask> publicationTasks(@Param("tenant") long tenant,@Param("state") String state,@Param("after") long after,@Param("limit") int limit);
    /** 事件锁之后当前锁读隔离任务，不能从RR历史快照选重试基数。 */
    List<JobLease> lockReviewedPublication(@Param("tenant") long tenant,@Param("eventId") String eventId);
    /** 只重启发布隔离任务，不重置全局令牌和累计次数。 */
    int requeuePublication(@Param("tenant") long tenant,@Param("eventId") String eventId);
    /** 通过类型/可运行/租户索引直接定位下一个租户，不遍历该租户的所有任务。 */
    List<Long> nextTenant(@Param("type") String type,@Param("after") long after);
    /** 唯一业务键只占位，重放不重置任务历史。 */
    int insertJob(@Param("tenant") long tenant, @Param("type") String type, @Param("business") String business,@Param("traceParent") String traceParent);
    /** 首次预约，不借重复插入重启已完成任务。 */
    int schedule(@Param("tenant") long tenant,@Param("type") String type,@Param("business") String business,
        @Param("runAt") java.time.Instant runAt,@Param("traceParent") String traceParent);
    /** 运行中只递增唤醒代数，不抢夺或延长租约。 */
    int wake(@Param("tenant") long tenant,@Param("type") String type,@Param("business") String business);
    /** 跳过已锁任务，单租户单类型有界领取，锁保留至本地租约提交。 */
    List<Long> claimable(@Param("tenant") long tenant, @Param("type") String type, @Param("limit") int limit);
    /** 已持行锁后递增接管令牌和尝试次数。 */
    int claim(@Param("tenant") long tenant, @Param("id") long id, @Param("owner") String owner, @Param("leaseMicros") long leaseMicros);
    /** 当前事务内读取刚写入的租约凭据。 */
    List<JobLease> read(@Param("tenant") long tenant, @Param("id") long id);
    /** 先取得行锁再执行带数据库当前时间的条件更新，避免锁等待使时间判断过时。 */
    List<JobLease> lock(@Param("tenant") long tenant, @Param("id") long id);
    /** 原工作者只能延长仍有效的租约，不能续活过期令牌。 */
    int renew(@Param("tenant") long tenant, @Param("id") long id, @Param("owner") String owner,
              @Param("token") long token, @Param("leaseMicros") long leaseMicros, @Param("type") String type, @Param("business") String business);
    /** 完成或退避必须匹配当前有效令牌，与调用方业务写入共享事务。 */
    int finish(@Param("tenant") long tenant, @Param("id") long id, @Param("owner") String owner,
               @Param("token") long token, @Param("state") String state, @Param("delayMicros") long delayMicros,
               @Param("error") String error, @Param("type") String type, @Param("business") String business);
}
