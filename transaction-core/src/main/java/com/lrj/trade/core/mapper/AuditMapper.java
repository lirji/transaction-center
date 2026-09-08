package com.lrj.trade.core.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** AuditWriter的持久化边界；SQL仅位于同名XML，事务由业务层统一编排。 */
@Mapper
public interface AuditMapper {
    /** 追加操作证据，业务回滚时审计必须同时回滚。 */
    int insertAudit(@Param("tenant") long tenant, @Param("actorKey") String actorKey, @Param("action") String action, @Param("resourceType") String resourceType, @Param("resourceId") String resourceId, @Param("reason") String reason, @Param("beforeVersion") long beforeVersion, @Param("afterVersion") long afterVersion);
}
