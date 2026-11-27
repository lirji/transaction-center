package com.lrj.trade.server.auditarchive;

import static com.lrj.trade.server.auditarchive.RefundArchivePayload.MAX_FACTS;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.AuditWriter;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.core.mapper.AuditMapper;
import com.lrj.trade.refund.AdminRefundQueryService;
import com.lrj.trade.server.auditarchive.RefundArchiveMapper.Batch;
import com.lrj.trade.server.auditarchive.RefundArchiveMapper.Grant;
import com.lrj.trade.server.auditarchive.RefundArchivePayload.Snapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 归档仅保存已确认退款的脱敏证据与关联，不调用资金、审批或渠道写入。 */
final class RefundAuditArchive {
    private static final String CLAIMED = "CLAIMED";
    private static final String UPLOADED = "UPLOADED";
    private static final String VERIFIED = "VERIFIED";
    private final long tenant;
    private final Duration minimumAge;
    private final Clock clock;
    private final RefundArchiveMapper mapper;
    private final AuditMapper audits;
    private final AuditWriter audit;
    private final AdminRefundQueryService refunds;
    private final RefundArchiveObjects objects;
    private final TransactionTemplate transaction;
    private final Semaphore archive = new Semaphore(1);
    private final Semaphore recovery = new Semaphore(1);

    RefundAuditArchive(long tenant, Duration minimumAge, Clock clock, RefundArchiveMapper mapper,
            AuditMapper audits, AuditWriter audit, AdminRefundQueryService refunds, RefundArchiveObjects objects,
            PlatformTransactionManager manager) {
        this.tenant = tenant;
        this.minimumAge = minimumAge;
        this.clock = clock;
        this.mapper = mapper;
        this.audits = audits;
        this.audit = audit;
        this.refunds = refunds;
        this.objects = objects;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
    }

    /** 空参数补办一项中断批次；明确退款号时只处理配置租户的这笔退款。 */
    String verify(String refundNo) {
        if (!archive.tryAcquire()) throw unavailable();
        try {
            Batch batch;
            if (refundNo == null || refundNo.isBlank()) {
                batch = mapper.pending(tenant);
            } else {
                var refund = refunds.read(tenant, refundNo);
                batch = transaction.execute(status -> claim(Long.parseLong(refund.cursor()), refund.refundNo()));
            }
            return batch == null ? null : verifyBatch(batch);
        } finally {
            archive.release();
        }
    }

    /** 当前人员、工单与批次二次校验之后才把版本化对象发布到短期查询区。 */
    Snapshot restore(Actor actor, String ticket) {
        return audit.readRefundArchive(actor, ticket, true, () -> {
            var grant = requireGrant(actor, ticket, false);
            if (!recovery.tryAcquire()) throw unavailable();
            try {
                var batch = requireBatch(mapper.batch(tenant, grant.batchId()));
                var snapshot = RefundArchivePayload.decode(batch,
                        objects.recover(batch.objectKey(), batch.objectVersion(), batch.payloadBytes()));
                return transaction.execute(status -> {
                    if (!grant.equals(requireGrant(actor, ticket, true))) throw missing();
                    mapper.storeRestored(tenant, grant, RefundArchivePayload.encodeRestored(snapshot));
                    return snapshot;
                });
            } finally {
                recovery.release();
            }
        });
    }

    /** 工单授权和待展示投影均存在时才返回，并单独保留读取尝试。 */
    Snapshot restored(Actor actor, String ticket) {
        return audit.readRefundArchive(actor, ticket, false, () -> {
            var grant = requireGrant(actor, ticket, false);
            String payload = mapper.readRestored(tenant, ticket, actor.key());
            if (payload == null || !grant.equals(requireGrant(actor, ticket, false))) throw missing();
            return RefundArchivePayload.decodeRestored(payload);
        });
    }

    /** 每次只释放有界的过期临时副本，归档原件与来源业务表继续保全。 */
    int cleanExpiredRestorations() {
        return mapper.removeExpiredRestorations(tenant);
    }

    private Batch claim(long id, String nativeRefundNo) {
        Instant cutoff = clock.instant().minus(minimumAge);
        var subject = mapper.subject(tenant, id, cutoff);
        if (subject == null) return null;
        var operations = audits.refundHistory(tenant, nativeRefundNo, null, null, MAX_FACTS + 1);
        var references = mapper.references(tenant, id, MAX_FACTS + 1);
        int count = operations.size() + references.size();
        if (count == 0) return null;
        if (count > MAX_FACTS) throw new TradeException("EVIDENCE_UNAVAILABLE", "交易退款档案超过单批证据预算");
        Instant from = subject.updatedAt();
        Instant until = subject.updatedAt();
        for (Instant occurred : java.util.stream.Stream.concat(
                operations.stream().map(AuditMapper.Row::occurredAt), references.stream().map(RefundArchiveMapper.Fact::occurredAt)).toList()) {
            if (occurred.isBefore(from)) from = occurred;
            if (occurred.isAfter(until)) until = occurred;
        }
        Instant lastOutcome = mapper.latestOutcome(tenant, nativeRefundNo);
        if (lastOutcome != null && lastOutcome.isAfter(until)) until = lastOutcome;
        if (until.isAfter(cutoff)) return null;
        var payload = RefundArchivePayload.encode(new Snapshot(subject, operations, references));
        String digest = RefundArchivePayload.digest(payload);
        String batchId = UUID.randomUUID().toString();
        mapper.claim(new Batch(tenant, batchId, id, subject.refundNo(), RefundArchivePayload.FORMAT, from, until,
                count, objectKey(tenant, batchId), null, digest, payload.length, payload, CLAIMED));
        var claimed = mapper.batchByDigest(tenant, id, digest);
        if (claimed == null) throw new TradeException("EVIDENCE_UNAVAILABLE", "请先补办本租户待核验的档案批次");
        return requireBatch(claimed);
    }

    private String verifyBatch(Batch batch) {
        requireBatch(batch);
        if (CLAIMED.equals(batch.state())) {
            validateSnapshot(batch, batch.payload());
            String version = objects.putOnce(batch.objectKey(), batch.payload(), batch.sha256());
            mapper.uploaded(tenant, batch.batchId(), version);
            var stored = requireBatch(mapper.batch(tenant, batch.batchId()));
            if (!Objects.equals(version, stored.objectVersion())) throw unavailable();
            batch = stored;
        }
        if (UPLOADED.equals(batch.state())) {
            var exact = objects.verify(batch.objectKey(), batch.objectVersion(), batch.payloadBytes());
            validateSnapshot(batch, exact);
            if (!Arrays.equals(exact, batch.payload())) throw unavailable();
            mapper.verified(tenant, batch.batchId(), batch.objectVersion());
            batch = requireBatch(mapper.batch(tenant, batch.batchId()));
        }
        if (!VERIFIED.equals(batch.state())) throw unavailable();
        return batch.batchId();
    }

    private void validateSnapshot(Batch batch, byte[] bytes) {
        if (bytes == null) throw unavailable();
        RefundArchivePayload.decode(batch, bytes);
    }

    private Grant requireGrant(Actor actor, String ticket, boolean lock) {
        if (actor.tenantId() != tenant || ticket == null || !ticket.matches("[A-Za-z0-9_-]{1,64}")) throw missing();
        var grant = mapper.grant(tenant, ticket, actor.key(), lock);
        if (grant == null) throw missing();
        return grant;
    }

    private Batch requireBatch(Batch batch) {
        if (batch == null || batch.tenantId() != tenant || !objectKey(tenant, batch.batchId()).equals(batch.objectKey())) throw unavailable();
        return batch;
    }

    private static String objectKey(long tenant, String id) {
        return "trade/operation-audit/v1/" + tenant + "/" + id + ".ndjson.gz";
    }

    private static TradeException missing() {
        return new TradeException("NOT_FOUND", "恢复工单不存在或已失效");
    }

    private static TradeException unavailable() {
        return new TradeException("EVIDENCE_UNAVAILABLE", "交易退款档案暂不可用");
    }
}
