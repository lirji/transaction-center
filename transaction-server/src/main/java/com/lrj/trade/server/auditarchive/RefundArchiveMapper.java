package com.lrj.trade.server.auditarchive;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 归档只读取同租户的退款事实和受控关联，持久写入仅限归档与临时读取表。 */
@Mapper
public interface RefundArchiveMapper {
    record Subject(long id, long tenantId, String refundNo, String orderNo, String approvalNo,
                   String receiptNo, String amountMinor, String currency, String state, Instant updatedAt) {}

    record Fact(String kind, String sourceId, Instant occurredAt, String type, String result, String reference,
                String relation) {}

    record Batch(long tenantId, String batchId, long refundId, String refundNo, int schemaVersion,
                 Instant factsFrom, Instant factsUntil, int factCount, String objectKey, String objectVersion,
                 String sha256, int payloadBytes, byte[] payload, String state) {}

    record Grant(String ticketId, String batchId, Instant expiresAt) {}

    /** 从本退款的权威记录取固定标识，只领取已达指定年龄的已确认终态。 */
    Subject subject(@Param("tenant") long tenant, @Param("id") long id, @Param("cutoff") Instant cutoff);

    /** 读取未跨退款的验真渠道引用、审批证据及原订单和明细一致的退货上下文。 */
    List<Fact> references(@Param("tenant") long tenant, @Param("id") long id, @Param("limit") int limit);

    Instant latestOutcome(@Param("tenant") long tenant, @Param("refundNo") String refundNo);

    int claim(@Param("batch") Batch batch);

    Batch batchByDigest(@Param("tenant") long tenant, @Param("refundId") long refundId, @Param("sha256") String sha256);

    Batch batch(@Param("tenant") long tenant, @Param("id") String id);

    Batch pending(@Param("tenant") long tenant);

    int uploaded(@Param("tenant") long tenant, @Param("id") String id, @Param("version") String version);

    int verified(@Param("tenant") long tenant, @Param("id") String id, @Param("version") String version);

    Grant grant(@Param("tenant") long tenant, @Param("ticket") String ticket, @Param("actor") String actor,
                @Param("lock") boolean lock);

    int storeRestored(@Param("tenant") long tenant, @Param("grant") Grant grant, @Param("payload") String payload);

    String readRestored(@Param("tenant") long tenant, @Param("ticket") String ticket, @Param("actor") String actor);

    int removeExpiredRestorations(@Param("tenant") long tenant);
}
