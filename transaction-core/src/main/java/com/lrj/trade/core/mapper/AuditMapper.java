package com.lrj.trade.core.mapper;

import com.lrj.trade.core.AuditWriter.RefundEffect;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** AuditWriter的持久化边界；SQL仅位于同名XML，事务由业务层统一编排。 */
@Mapper
public interface AuditMapper {
    /** 追加操作证据，业务回滚时审计必须同时回滚。 */
    int insertAudit(@Param("tenant") long tenant, @Param("actorKey") String actorKey, @Param("action") String action, @Param("resourceType") String resourceType, @Param("resourceId") String resourceId, @Param("reason") String reason, @Param("beforeVersion") long beforeVersion, @Param("afterVersion") long afterVersion);

    record Attempt(long tenant,String requestId,String actorKey,String action,String resourceId,String traceId) {}
    record Row(String id,String requestId,String traceId,String action,String actorId,String resourceId,
               Instant occurredAt,String result,String errorCode,String effectId,Long beforeVersion,Long afterVersion,
               String orderNo,String businessNo,String amountMinor,String currency,String beforeState,String afterState) {}

    /** 稳定入口事实，独立于业务回滚持久化。 */
    int insertAttempt(Attempt attempt);
    /** 使用租户与请求唯一键找回本地引用。 */
    long findAttempt(@Param("tenant") long tenant,@Param("requestId") String requestId);
    /** 追加失败或重放证据，并关联已授权的原效果。 */
    int insertOutcome(@Param("tenant") long tenant,@Param("attempt") long attempt,@Param("result") String result,
                      @Param("error") String error,@Param("resource") String resource,@Param("effect") Long effect);
    /** 退款效果仍写原表，与业务变更共同提交。 */
    int insertRefundEffect(@Param("tenant") long tenant,@Param("actorKey") String actorKey,@Param("action") String action,
                           @Param("resource") String resource,@Param("reason") String reason,@Param("before") long before,
                           @Param("after") long after,@Param("effect") RefundEffect effect);
    /** 当前尝试若已提交，返回本次效果。 */
    Long effectOfAttempt(@Param("tenant") long tenant,@Param("attempt") long attempt);
    /** 重放以已提交的租户、退款及动作定位原证据。 */
    Long findRefundEffect(@Param("tenant") long tenant,@Param("resource") String resource,@Param("action") String action);
    /** 有界读取授权父资源的实际效果和请求结果。 */
    List<Row> refundHistory(@Param("tenant") long tenant,@Param("resource") String resource,@Param("before") Instant before,
                            @Param("cursor") String cursor,@Param("limit") int limit);
}
