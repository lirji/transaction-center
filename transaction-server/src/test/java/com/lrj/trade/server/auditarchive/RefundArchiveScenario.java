package com.lrj.trade.server.auditarchive;

import static org.junit.jupiter.api.Assertions.*;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.AuditWriter;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.core.mapper.AuditMapper;
import com.lrj.trade.refund.AdminRefundQueryService;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 用来源应用的真实 Mapper 和版本桶，验证独立恢复不会改动退款/资金。 */
public final class RefundArchiveScenario {
    private RefundArchiveScenario() {}

    public static void verify(ApplicationContext context, JdbcTemplate jdbc, Actor owner, String nativeRefundNumber,
            String refundNumber, String orderNumber, String approvalReceipt, String callbackNumber, String returnNumber,
            org.junit.jupiter.api.function.Executable checkAuthorizedHttp) throws Throwable {
        var config = new RefundArchiveConfiguration();
        long tenant = owner.tenantId();
        var writer = context.getBean(AuditWriter.class);
        var before = writer.refundHistory(owner, nativeRefundNumber, null);
        try (var storage = config.refundArchiveObjects(URI.create(System.getenv("TRADE_ARCHIVE_TEST_ENDPOINT")),
                "trade-audit-verification", "us-east-1",
                System.getenv("TRADE_ARCHIVE_WRITE_KEY"), System.getenv("TRADE_ARCHIVE_WRITE_SECRET"),
                System.getenv("TRADE_ARCHIVE_RESTORE_KEY"), System.getenv("TRADE_ARCHIVE_RESTORE_SECRET"))) {
            var loseUpload = new AtomicBoolean(true);
            var corruptRestore = new AtomicBoolean(false);
            var controlled = new RefundArchiveObjects() {
                @Override
                public String putOnce(String key, byte[] bytes, String sha256) {
                    String version = storage.putOnce(key, bytes, sha256);
                    if (loseUpload.getAndSet(false)) throw new TradeException("EVIDENCE_UNAVAILABLE", "传输回包模拟丢失");
                    return version;
                }

                @Override
                public byte[] verify(String key, String version, int bytes) {
                    return storage.verify(key, version, bytes);
                }

                @Override
                public byte[] recover(String key, String version, int bytes) {
                    var result = storage.recover(key, version, bytes);
                    if (corruptRestore.get()) result[0] ^= 1;
                    return result;
                }
            };
            var mapper = context.getBean(RefundArchiveMapper.class);
            var workflow = new RefundAuditArchive(tenant, Duration.ofDays(1), Clock.offset(Clock.systemUTC(), Duration.ofDays(3)),
                    mapper, context.getBean(AuditMapper.class), writer, context.getBean(AdminRefundQueryService.class), controlled,
                    context.getBean(PlatformTransactionManager.class));

            assertEquals("传输回包模拟丢失", assertThrows(TradeException.class, () -> workflow.verify(refundNumber)).getMessage());
            var claimed = mapper.pending(tenant);
            assertNotNull(claimed);
            assertEquals("CLAIMED", claimed.state());
            assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update("""
                INSERT INTO tc_refund_audit_archive_batch(tenant_id,batch_id,refund_id,refund_no,schema_version,
                  facts_from,facts_until,fact_count,object_key,sha256,payload_bytes,payload,state)
                SELECT tenant_id,UUID(),refund_id,refund_no,schema_version,facts_from,facts_until,fact_count,object_key,
                  REPEAT('a',64),payload_bytes,payload,state FROM tc_refund_audit_archive_batch
                WHERE tenant_id=? AND batch_id=?
                """, tenant, claimed.batchId()));
            assertEquals(claimed.batchId(), workflow.verify(null));
            assertEquals(claimed.batchId(), workflow.verify(refundNumber));
            var verified = mapper.batch(tenant, claimed.batchId());
            assertEquals("VERIFIED", verified.state());
            assertNotNull(verified.objectVersion());
            assertNull(verified.payload());
            assertNull(workflow.verify(null));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tc_refund_audit_archive_batch WHERE tenant_id=?", Integer.class, tenant));

            String ticket = "refund-archive-review";
            var recoverer = new Actor(tenant, owner.issuer(), "archive-recoverer");
            var reviewer = new Actor(tenant, owner.issuer(), "archive-reviewer");
            jdbc.update("""
                INSERT INTO tc_refund_audit_archive_grant(tenant_id,ticket_id,batch_id,actor_key,approved_by,expires_at)
                VALUES(?,?,?,?,?,UTC_TIMESTAMP(3)+INTERVAL 1 HOUR)
                """, tenant, ticket, verified.batchId(), recoverer.key(), reviewer.key());
            assertMissing(() -> workflow.restore(new Actor(tenant + 1, recoverer.issuer(), recoverer.subject()), ticket));
            assertMissing(() -> workflow.restore(reviewer, ticket));
            corruptRestore.set(true);
            assertEquals("EVIDENCE_UNAVAILABLE", assertThrows(TradeException.class, () -> workflow.restore(recoverer, ticket)).code());
            assertMissing(() -> workflow.restored(recoverer, ticket));
            corruptRestore.set(false);

            var restored = workflow.restore(recoverer, ticket);
            assertEquals(restored, workflow.restore(recoverer, ticket));
            assertEquals(restored, workflow.restored(recoverer, ticket));
            assertEquals(refundNumber, restored.refund().refundNo());
            assertEquals(orderNumber, restored.refund().orderNo());
            assertEquals("100", restored.refund().amountMinor());
            assertEquals("SUCCEEDED", restored.refund().state());
            assertEquals(before.items(), restored.operations());
            var facts = restored.references().stream().collect(Collectors.toMap(RefundArchiveMapper.Fact::kind, row -> row));
            assertEquals(Map.of("APPROVAL", approvalReceipt, "CHANNEL", callbackNumber, "RETURN", returnNumber),
                    facts.values().stream().collect(Collectors.toMap(RefundArchiveMapper.Fact::kind, RefundArchiveMapper.Fact::reference)));
            assertEquals("ORDER_ITEM", facts.get("RETURN").relation());
            assertEquals("REFUND", facts.get("CHANNEL").relation());
            assertEquals("PROCESSED", facts.get("CHANNEL").result());
            assertEquals(before, writer.refundHistory(owner, nativeRefundNumber, null));
            checkAuthorizedHttp.execute();

            jdbc.update("UPDATE tc_refund_audit_archive_grant SET revoked_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND ticket_id=?", tenant, ticket);
            assertMissing(() -> workflow.restored(recoverer, ticket));
            jdbc.update("UPDATE tc_refund_audit_archive_restored SET expires_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND ticket_id=?", tenant, ticket);
            assertEquals(1, workflow.cleanExpiredRestorations());
            assertEquals(0, workflow.cleanExpiredRestorations());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tc_refund_approval_receipt WHERE tenant_id=?", Integer.class, tenant));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tc_refund_callback WHERE tenant_id=?", Integer.class, tenant));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tc_money_ledger WHERE tenant_id=? AND entry_type='REFUND'", Integer.class, tenant));
            assertTrue(jdbc.queryForObject("""
                SELECT COUNT(*) FROM tc_audit_operation_attempt WHERE tenant_id=?
                  AND action_code IN('refund.audit.archive.restore','refund.audit.archive.read')
                """, Integer.class, tenant) >= 5);
        }
    }

    private static void assertMissing(org.junit.jupiter.api.function.Executable action) {
        assertEquals("NOT_FOUND", assertThrows(TradeException.class, action).code());
    }
}
