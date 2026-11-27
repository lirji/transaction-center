package com.lrj.trade.core.query;

import com.lrj.trade.core.TradeException;
import com.lrj.trade.core.mapper.AuditMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 聚合现有业务审计，不读取原因原文、请求载荷或专岗证据。 */
@Service
public class OperationAuditQuery {
    private static final Pattern CURSOR = Pattern.compile("([1-9][0-9]{3}-[^,]{10,25}Z),([ER])([1-9][0-9]{0,18})");

    private final AuditMapper mapper;

    /** 复用审计 Mapper，以同一语句、同一租户读取两类记录。 */
    public OperationAuditQuery(AuditMapper mapper) {
        this.mapper = mapper;
    }

    /** 用一条探测记录判断是否有下一页，不扫描全租户计数。 */
    @Transactional(readOnly = true, timeout = 3)
    public Page page(long tenant, AdminQuery query, String cursor) {
        List<Entry> rows = mapper.operationHistory(tenant, query, parseCursor(cursor));
        List<Entry> items = rows.stream().limit(query.limit()).toList();
        String nextCursor = rows.size() > query.limit() ? cursorFor(items.getLast()) : null;
        return new Page(items, nextCursor);
    }

    /** 只暴露业务定位字段和可信身份摘要。通用旧记录可能没有追踪编号。 */
    public record Entry(String id, Instant occurredAt, String resourceType, String resourceId, String action,
                        String actorId, String result, String errorCode, String traceId) {}

    /** 游标向过去翻页，没有进一步数据时返回空。 */
    public record Page(List<Entry> items, String nextCursor) {}

    /** 时间相同时，按来源及数字主键排序，避免跨表主键相同或位数变化导致漏读。 */
    public record Position(Instant time, boolean outcome, long id) {}

    private static Position parseCursor(String cursor) {
        if (cursor == null) return null;
        var token = CURSOR.matcher(cursor);
        if (cursor.length() > 55 || !token.matches()) throw invalidCursor();
        try {
            Instant time = Instant.parse(token.group(1));
            if (time.getNano() % 1_000_000 != 0 || !time.toString().equals(token.group(1))) throw invalidCursor();
            return new Position(time, token.group(2).equals("R"), AdminQuery.number(token.group(3), false));
        } catch (DateTimeParseException ex) {
            throw invalidCursor();
        }
    }

    private static String cursorFor(Entry entry) {
        return entry.occurredAt() + "," + entry.id();
    }

    private static TradeException invalidCursor() {
        return new TradeException("INVALID_ARGUMENT", "审计查询游标无效");
    }
}
