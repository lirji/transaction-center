package com.lrj.trade.core.mapper;

import com.lrj.trade.core.OutboxDeliveryPort.Row;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 发布状态SQL与业务SQL同样限定租户，禁止工作者直接操作持久层。 */
@Mapper
public interface OutboxDeliveryMapper {
    /** 当前读与后续状态变更共用行锁。 */
    List<Row> lock(@Param("tenant") long tenant,@Param("eventId") String eventId);
    /** 只有待发送状态可以记成功；位置与时间和任务完成同事务。 */
    int sent(@Param("tenant") long tenant,@Param("eventId") String eventId,@Param("topic") String topic,@Param("partition") int partition,@Param("offset") long offset);
    /** 失败历史保留首尾时间，终态不被覆盖。 */
    int failed(@Param("tenant") long tenant,@Param("eventId") String eventId,@Param("code") String code,@Param("status") String status);
    /** 有界扫描全部状态/类型以推进游标，由服务白名单筛选，不无界搜索。 */
    List<Row> page(@Param("tenant") long tenant,@Param("after") long after,@Param("limit") int limit);
}
