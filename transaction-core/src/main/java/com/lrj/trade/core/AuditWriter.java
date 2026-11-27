package com.lrj.trade.core;

import com.lrj.trade.core.mapper.AuditMapper;
import com.lrj.trade.core.mapper.AuditMapper.Attempt;
import com.lrj.trade.core.mapper.AuditMapper.Row;
import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.core.query.AdminNumberedPage;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;

/** 审计成功证据加入业务事务，请求尝试和失败事实独立持久化。 */
@Component
public class AuditWriter {
    private static final Logger LOG=LoggerFactory.getLogger(AuditWriter.class);
    private static final int DEFAULT_PAGE_SIZE=50;
    private static final String READ_ORDER_AUDIT="order.audit.evidence.read";
    private static final String READ_REFUND_AUDIT="refund.audit.list";
    private static final String RESTORE_REFUND_ARCHIVE="refund.audit.archive.restore";
    private static final String READ_REFUND_ARCHIVE="refund.audit.archive.read";
    private static final String INVALID_CURSOR="审计查询游标无效";
    private static final Pattern SENSITIVE=Pattern.compile(
        "(?iu)bearer\\s|token\\s*[:=]|password\\s*[:=]|cookie\\s*[:=]|密码\\s*[：:]|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|[0-9][0-9 ()+.-]{8,}[0-9]");

    private final AuditMapper mapper;
    private final TransactionTemplate independent;
    private final TransactionTemplate historySnapshot;

    /** 复用本地事务数据源，独立的尝试事务有短超时。 */
    public AuditWriter(AuditMapper mapper,PlatformTransactionManager manager) {
        this.mapper=mapper;
        this.independent=new TransactionTemplate(manager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.independent.setTimeout(3);
        this.historySnapshot = new TransactionTemplate(manager);
        this.historySnapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.historySnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.historySnapshot.setReadOnly(true);
        this.historySnapshot.setTimeout(3);
    }

    /** 既有调用只保留动作、资源、原因及版本。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(Actor actor,String action,String type,String resource,String reason,long before,long after) {
        Checks.text(action,64); Checks.text(type,64); Checks.text(resource,64); Checks.text(reason,255);
        mapper.insertAudit(actor.tenantId(),actor.key(),action,type,resource,reason,before,after);
    }

    /** 由退款用例使用本地可信记录构建的领域提交字段。 */
    public record RefundEffect(Long attemptId,String orderNo,String businessNo,long amountMinor,String currency,
                               String beforeState,String afterState,String traceId) {}

    /** 实际退款边界与父订单引用从事务内可信对象取得。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void appendRefund(Actor actor,String action,String refundNo,String reason,long before,long after,
                             RefundEffect effect) {
        Checks.text(action,64); Checks.text(refundNo,64); Checks.text(reason,255);
        expectWritten(mapper.insertRefundEffect(actor.tenantId(),actor.key(),action,refundNo,
            SENSITIVE.matcher(reason).find()?"已脱敏":reason,before,after,effect));
    }

    /** 保存可信尝试，确认回滚后以最小错误码结束。无归属证明时不绑定业务对象。 */
    public <T> T capture(Actor actor,String action,String verifiedResource,LongFunction<T> operation) {
        String requestId=UUID.randomUUID().toString();
        long id=independent.execute(status->{
            expectWritten(mapper.insertAttempt(actor.tenantId(),new Attempt(requestId,actor.key(),action,
                verifiedResource,traceId())));
            return mapper.findAttempt(actor.tenantId(),requestId);
        });
        try {
            return operation.apply(id);
        } catch(RuntimeException failure) {
            String code=failure instanceof TradeException trade?trade.code():null;
            try {
                independent.executeWithoutResult(status->expectWritten(mapper.insertOutcome(actor.tenantId(),id,
                    failureResult(code),code,verifiedResource,null)));
            } catch(RuntimeException unavailable) {
                LOG.error("交易审计结果写入失败 attemptId={} errorType={}",id,unavailable.getClass().getSimpleName());
            }
            throw failure;
        }
    }

    /** 提交路径已写本次效果时直接返回。原命令已经执行则关联原证据。 */
    public void resolveRefundReplay(long tenant,long attempt,String refundNo,String action) {
        if(mapper.effectOfAttempt(tenant,attempt)!=null)return;
        Long effect=mapper.findRefundEffect(tenant,refundNo,action);
        expectWritten(mapper.insertOutcome(tenant,attempt,"REPLAYED",null,refundNo,effect));
    }

    /** 只有已通过退款管理父资源授权的入口调用，返回前记录证据读取。 */
    public History refundHistory(Actor actor,String refundNo,String cursor) {
        return refundHistory(actor, refundNo, cursor, DEFAULT_PAGE_SIZE);
    }

    /** 调整页容量只影响本次取证范围，读取仍须成功留痕后才能返回。 */
    public History refundHistory(Actor actor, String refundNo, String cursor, int limit) {
        if (limit < 1 || limit > AdminQuery.MAX_LIMIT) {
            throw AdminQuery.invalid();
        }
        return capture(actor,READ_REFUND_AUDIT,refundNo,attempt->{
            var position=parseCursor(cursor);
            List<Row> rows=mapper.refundHistory(actor.tenantId(), refundNo, position.time(), position.id(), limit + 1, null, limit + 1);
            List<Row> items=rows.stream().limit(limit).toList();
            String next=rows.size()>limit?items.getLast().occurredAt()+","+items.getLast().id():null;
            expectWritten(mapper.insertOutcome(actor.tenantId(),attempt,"SUCCEEDED",null,refundNo,null));
            return new History(items,next);
        });
    }

    /** 授权后先读取一致快照，再记录查阅成功，查阅自身不会混入退款历史。 */
    public AdminNumberedPage<Row> numberedRefundHistory(Actor actor, String refundNo, int limit, long page) {
        return capture(actor, READ_REFUND_AUDIT, refundNo, attempt -> {
            var observedAt = Instant.now();
            var result = historySnapshot.execute(status -> AdminNumberedPage.of(page, limit,
                    mapper.refundHistoryCount(actor.tenantId(), refundNo), observedAt,
                    offset -> mapper.refundHistory(actor.tenantId(), refundNo, null, null, limit, offset, offset + limit)));
            expectWritten(mapper.insertOutcome(actor.tenantId(), attempt, "SUCCEEDED", null, refundNo, null));
            return result;
        });
    }

    /** 汇总参照真实订单范围，读取事实同样保持审计可追溯。 */
    public <T> T readOrderEvidence(Actor actor,String orderNo,java.util.function.Supplier<T> read) {
        return capture(actor,READ_ORDER_AUDIT,orderNo,attempt->{
            T evidence=read.get();
            expectWritten(mapper.insertOutcome(actor.tenantId(),attempt,"SUCCEEDED",null,orderNo,null));
            return evidence;
        });
    }

    /** 写入恢复或查阅尝试及结果，敏感内容仅在留痕成功后返回。 */
    public <T> T readRefundArchive(Actor actor, String ticket, boolean restore, java.util.function.Supplier<T> read) {
        String action = restore ? RESTORE_REFUND_ARCHIVE : READ_REFUND_ARCHIVE;
        String reference = ticket != null && ticket.matches("[A-Za-z0-9_-]{1,64}") ? ticket : null;
        return capture(actor, action, reference, attempt -> {
            T evidence = read.get();
            expectWritten(mapper.insertOutcome(actor.tenantId(), attempt, "SUCCEEDED", null, reference, null));
            return evidence;
        });
    }

    /** 将追踪与业务关联标识分开，缺上下文保留空值。 */
    public static String traceId() {
        return TraceContext.current()==null?null:TraceContext.current().traceId();
    }

    /** 审计视图的安全字段和有界游标。 */
    public record History(List<Row> items,String nextCursor) {}
    /** 分页时用记录时间和流水号保持稳定顺序。 */
    private record Position(Instant time,String id) {}

    private static Position parseCursor(String cursor) {
        if(cursor==null)return new Position(null,null);
        String[] parts=cursor.split(",",-1);
        if(cursor.length()>55||parts.length!=2||!parts[1].matches("[ER][0-9]{1,20}")||!parts[0].matches("[1-9][0-9]{3}-.*Z"))
            throw new TradeException("INVALID_ARGUMENT",INVALID_CURSOR);
        try {
            return new Position(Instant.parse(parts[0]),parts[1]);
        } catch(DateTimeParseException failure) {
            throw new TradeException("INVALID_ARGUMENT",INVALID_CURSOR);
        }
    }

    private static String failureResult(String code) {
        if(code==null)return "UNKNOWN";
        if(code.endsWith("CONFLICT"))return "CONFLICT";
        if(code.equals("FORBIDDEN")||code.equals("REFUND_APPROVAL_UNTRUSTED"))return "DENIED";
        return "REJECTED";
    }

    private static void expectWritten(int changed) {
        if(changed!=1)throw new IllegalStateException("交易审计写入结果无效");
    }
}
