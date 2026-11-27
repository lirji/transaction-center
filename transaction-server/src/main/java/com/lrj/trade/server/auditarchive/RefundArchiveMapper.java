package com.lrj.trade.server.auditarchive;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 归档只读取同租户的退款事实和受控关联，持久写入仅限归档与临时读取表。 */
@Mapper
public interface RefundArchiveMapper {
    /** 退款档案的限域父记录，展示号与源事实引用随快照固定。 */
    record Subject(long id, long tenantId, String refundNo, String orderNo, String approvalNo,
                   String receiptNo, String amountMinor, String currency, String state, Instant updatedAt) {}

    /** 以来源主键关联验真证据，退货上下文以关系码标识。 */
    record Fact(String kind, String sourceId, Instant occurredAt, String type, String result, String reference,
                String relation) {}

    /** 按租户和快照摘要复用清单，临时内容仅在核验前持有。 */
    record Batch(long tenantId, String batchId, long refundId, String refundNo, int schemaVersion,
                 Instant factsFrom, Instant factsUntil, int factCount, String objectKey, String objectVersion,
                 String sha256, int payloadBytes, byte[] payload, String state) {}

    /** 恢复所需的工单、批次及明确有效期。 */
    record Grant(String ticketId, String batchId, Instant expiresAt) {}

    /** 从本退款的权威记录取固定标识，只领取已达指定年龄的已确认终态。 */
    Subject subject(@Param("tenant") long tenant, @Param("id") long id, @Param("cutoff") Instant cutoff);

    /** 读取未跨退款的验真渠道引用、审批证据及原订单和明细一致的退货上下文。 */
    List<Fact> references(@Param("tenant") long tenant, @Param("id") long id, @Param("limit") int limit);

    /** 核对结果的实际记账时间，以免晚到证据按过早的尝试时间归档。 */
    Instant latestOutcome(@Param("tenant") long tenant, @Param("refundNo") String refundNo);

    /** 显式限定租户领取，数据库同时约束每租户一个待核验批次。 */
    int claim(@Param("tenant") long tenant, @Param("batch") Batch batch);

    /** 锁定刚领取或并发领取的同一快照，读到最新状态。 */
    Batch batchByDigest(@Param("tenant") long tenant, @Param("refundId") long refundId, @Param("sha256") String sha256);

    /** 按租户和已授权批次读取归档清单。 */
    Batch batch(@Param("tenant") long tenant, @Param("id") String id);

    /** 读取本租户唯一未核验批次，供回包丢失后补办。 */
    Batch pending(@Param("tenant") long tenant);

    /** 记录精确对象版本，只有领取状态允许首次推进。 */
    int uploaded(@Param("tenant") long tenant, @Param("id") String id, @Param("version") String version);

    /** 按回读成功的版本确认并释放临时快照。 */
    int verified(@Param("tenant") long tenant, @Param("id") String id, @Param("version") String version);

    /** 当前人员、租户、工单和批次都有效时才给恢复。 */
    Grant grant(@Param("tenant") long tenant, @Param("ticket") String ticket, @Param("actor") String actor);

    /** 发布恢复副本前锁住同一工单，再次核对撤销状态和人员。 */
    Grant lockGrant(@Param("tenant") long tenant, @Param("ticket") String ticket, @Param("actor") String actor);

    /** 锁内写入已经过校验的精确档案临时副本。 */
    int storeRestored(@Param("tenant") long tenant, @Param("grant") Grant grant, @Param("payload") String payload);

    /** 复核有效工单、租户、人员与实际副本批次。 */
    String readRestored(@Param("tenant") long tenant, @Param("ticket") String ticket, @Param("actor") String actor);

    /** 按租户有界删除已经过期的临时副本。 */
    int removeExpiredRestorations(@Param("tenant") long tenant);
}
