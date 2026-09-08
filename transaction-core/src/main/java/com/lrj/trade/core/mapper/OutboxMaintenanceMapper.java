package com.lrj.trade.core.mapper;
import com.lrj.trade.core.*;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 维护控制行和候选查询边界；只有nextTenant允许内部跨租户发现。 */
@Mapper
public interface OutboxMaintenanceMapper {
    /** (tenant,id)索引seek，结果至多一行，无DISTINCT/GROUP全表聚合。 */
    List<Long> nextTenant(@Param("after") long after);
    /** 先占租户唯一键，避免缺失行间隙锁升级。 */
    int ensure(@Param("tenant") long tenant);
    /** 控制行锁串行同租户的多个维护者。 */
    List<OutboxMaintenanceState> lock(@Param("tenant") long tenant);
    /** 本轮固定上界取当前已提交最大ID，不追赶之后的新记录。 */
    Long upper(@Param("tenant") long tenant);
    /** 启动新轮次保留上次完成证据，不重置完成累计数。 */
    int start(@Param("tenant") long tenant,@Param("upper") long upper);
    /** RC普通读，禁止增加Outbox行锁形成与发布器的逆序等待。 */
    List<OutboxMaintenanceCandidate> page(@Param("tenant") long tenant,@Param("after") long after,@Param("upper") long upper,@Param("limit") int limit);
    /** 入队和游标统计同事务，完成时才设置下一轮时间。 */
    int advance(@Param("tenant") long tenant,@Param("cursor") long cursor,@Param("scanned") long scanned,@Param("candidates") long candidates,@Param("complete") boolean complete,@Param("intervalMicros") long intervalMicros);
}
