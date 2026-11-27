package com.lrj.trade.server.operations;

import com.lrj.trade.core.query.AdminPage;
import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.core.query.AdminNumberedPage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

/** 只读应用服务为聚合和明细提供统一观察时间与分页语义。 */
@Service
public class OperationsReportService {
    private final OperationsReportMapper mapper;

    /** 注入集中维护汇总和明细口径的仓储。 */
    public OperationsReportService(OperationsReportMapper mapper) { this.mapper=mapper; }

    /** 观察时间记录查询开始时刻，与用户可选统计区间独立。 */
    public OperationsReport.Summary summary(long tenant, String currency, AdminQuery query) {
        Instant observed=Instant.now();
        return OperationsReport.Summary.of(query.createdFrom(),query.createdTo(),observed,currency,
            mapper.summary(tenant,currency,query));
    }

    /** 同一快照输出状态与日期分组，生成真实的零业务日期。 */
    @Transactional(readOnly = true, timeout = 3, isolation = Isolation.REPEATABLE_READ)
    public OperationsReport.Analysis analysis(long tenant, String currency, AdminQuery query) {
        Instant observed = Instant.now();
        var states = mapper.orderStates(tenant, currency, query);
        var byDay = mapper.daily(tenant, currency, query).stream()
                .collect(Collectors.toMap(OperationsReport.DailyTotals::day, Function.identity()));
        // 北京时间使用固定 UTC+8，且末端不包含在统计范围内。
        ZoneOffset zone = ZoneOffset.ofHours(8);
        var first = query.createdFrom().atOffset(zone).toLocalDate();
        var last = query.createdTo().minusNanos(1).atOffset(zone).toLocalDate();
        var daily = first.datesUntil(last.plusDays(1)).map(day -> byDay.getOrDefault(day,
                new OperationsReport.DailyTotals(day, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO)).toView()).toList();
        return new OperationsReport.Analysis(query.createdFrom(), query.createdTo(), observed, currency,
                states.stream().mapToLong(OperationsReport.OrderState::payableCount).sum(),
                states.stream().mapToLong(OperationsReport.OrderState::convertedCount).sum(), states, daily);
    }

    /** 同快照读取准确的资金事实条数及指定页。 */
    @Transactional(readOnly = true, timeout = 3, isolation = Isolation.REPEATABLE_READ)
    public AdminNumberedPage<OperationsReport.Fact> numberedFacts(long tenant, String currency, AdminQuery query, long page) {
        Instant observed = Instant.now();
        return AdminNumberedPage.of(page, query.limit(), mapper.factCount(tenant, currency, query), observed,
                offset -> mapper.factsAtOffset(tenant, currency, query, offset));
    }

    /** 交易侧绑定在一个本地快照中读取，不代来源系统解释状态。 */
    @Transactional(readOnly = true, timeout = 3, isolation = Isolation.REPEATABLE_READ)
    public OperationsReport.LinkedCheckouts linked(long tenant, String currency, AdminQuery query, long page) {
        Instant observedAt = Instant.now();
        var totals = mapper.linkedTotals(tenant, currency, query);
        var rows = AdminNumberedPage.of(page, query.limit(), totals.linkedCount(), observedAt,
                offset -> mapper.linkedRows(tenant, currency, query, offset));
        return new OperationsReport.LinkedCheckouts(rows, totals.fundingCount(), totals.discount().toBigIntegerExact().toString());
    }

    /** 明细复用管理游标分页，并额外读取一条判断是否还有剩余事实。 */
    public AdminPage<OperationsReport.Fact> facts(long tenant, String currency, AdminQuery query) {
        Instant observed=Instant.now();
        return AdminPage.of(mapper.facts(tenant,currency,query),query.limit(),observed);
    }
}
