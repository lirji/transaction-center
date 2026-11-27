package com.lrj.trade.core.mapper;
import com.lrj.trade.core.*;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 永久重放凭证和后台摘要的持久边界，所有语句显式绑定租户。 */
@Mapper
public interface OutboxOperationsMapper {
    /** 唯一键先占位再当前读，避免RR缺失键间隙锁升级死锁。 */
    int insertReceipt(@Param("tenant") long tenant,@Param("actor") String actor,@Param("key") String key,@Param("eventId") String eventId,@Param("hash") String hash,@Param("reason") String reason,@Param("ticket") String ticket);
    /** 凭证锁先于事件和任务，已完成结果永久返回。 */
    List<OutboxReplayReceipt> lockReceipt(@Param("tenant") long tenant,@Param("actor") String actor,@Param("key") String key);
    /** 结果与事件/任务重启同事务提交，不留下已完成凭证却未调度的中间态。 */
    int finishReceipt(@Param("tenant") long tenant,@Param("id") long id,@Param("version") long version,@Param("replayCount") long replayCount);
    /** 原消息只改变发布状态与新轮基数，禁止修改载荷和eventId。 */
    int replay(@Param("tenant") long tenant,@Param("eventId") String eventId,@Param("version") long version,@Param("base") long base);
    /** 租户内有界队列，不包含敏感载荷。 */
    List<OutboxPublication> page(@Param("tenant") long tenant,@Param("state") String state,@Param("after") long after,@Param("limit") int limit);
    /** 与旧列表共享查询范围，计算指定条件下的全量条数。 */
    long pageCount(@Param("tenant") long tenant, @Param("state") String state);
    /** 取准确总量已核对后的页内安全投影。 */
    List<OutboxPublication> pageAtOffset(@Param("tenant") long tenant, @Param("state") String state, @Param("limit") int limit, @Param("offset") long offset);
}
