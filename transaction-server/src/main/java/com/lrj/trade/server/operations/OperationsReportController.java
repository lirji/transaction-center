package com.lrj.trade.server.operations;

import com.lrj.trade.core.TradeException;
import com.lrj.trade.core.query.AdminPage;
import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.server.AdminRead;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理范围以内提供有界经营查询，租户只取已验签的身份声明。 */
@RestController
@AdminRead
@RequestMapping("/api/v1/trade/admin/operations")
public class OperationsReportController {
    private static final Set<String> SCOPES=Arrays.stream(OperationsReport.Scope.values())
        .map(Enum::name).collect(Collectors.toUnmodifiableSet());
    private final OperationsReportService report;

    /** 保留标准管理限流，汇总失败可独立重试。 */
    public OperationsReportController(OperationsReportService report) { this.report=report; }

    /** 同一选定币种和统计期间内取完整订单与已确认资金。 */
    @GetMapping("/summary")
    public OperationsReport.Summary summary(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam MultiValueMap<String,String> params) {
        if (params.containsKey("after") || params.containsKey("limit")) throw invalid();
        AdminQuery query=AdminQuery.parse(params,Map.of("currency",Set.of()),true);
        return report.summary(tenant(jwt),validatedCurrency(query),query);
    }

    /** 明细限定为一种资金范围，可按主键继续翻页。 */
    @GetMapping("/financial-facts")
    public AdminPage<OperationsReport.Fact> facts(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam MultiValueMap<String,String> params) {
        AdminQuery query=AdminQuery.parse(params,Map.of("currency",Set.of(),"scope",SCOPES,"ledgerNo",Set.of()),true);
        String currency=validatedCurrency(query);
        if (!query.filters().containsKey("scope")) throw invalid();
        return report.facts(tenant(jwt),currency,query);
    }

    private static long tenant(Jwt jwt) { return AdminQuery.number(jwt.getClaimAsString("tenant_id"),false); }

    private static String validatedCurrency(AdminQuery query) {
        String currency=(String)query.filters().get("currency");
        if (query.createdFrom()==null || currency==null) throw invalid();
        return currency;
    }

    private static TradeException invalid() {
        return new TradeException("INVALID_ARGUMENT","经营查询需要完整时间窗、三位大写币种及有效资金范围");
    }
}
