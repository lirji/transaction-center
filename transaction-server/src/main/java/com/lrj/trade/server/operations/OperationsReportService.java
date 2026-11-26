package com.lrj.trade.server.operations;

import com.lrj.trade.core.query.AdminPage;
import com.lrj.trade.core.query.AdminQuery;
import java.time.Instant;
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

    /** 明细复用管理游标分页，并额外读取一条判断是否还有剩余事实。 */
    public AdminPage<OperationsReport.Fact> facts(long tenant, String currency, AdminQuery query) {
        Instant observed=Instant.now();
        return AdminPage.of(mapper.facts(tenant,currency,query),query.limit(),observed);
    }
}
