package com.lrj.trade.core.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Outbox的持久化边界；SQL仅位于同名XML，事务由业务层统一编排。 */
@Mapper
public interface OutboxMapper {
    /** 索引身份来自同一个不可变事件，完整JSON在业务事务中已序列化。 */
    int insertV1Event(@Param("tenant") long tenant,@Param("eventId") String eventId,@Param("eventType") String eventType,@Param("aggregateId") String aggregateId,@Param("payload") String payload);
    /** 只追加内部事件事实，必须参与调用方事务，不能视为已投递。 */
    int insertEvent(@Param("tenant") long tenant, @Param("eventId") String eventId, @Param("eventType") String eventType, @Param("aggregateId") String aggregateId, @Param("resourceId") String resourceId);
}
