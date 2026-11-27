package com.lrj.trade.server;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.AuditWriter;
import com.lrj.trade.core.OutboxOperationsService;
import com.lrj.trade.core.OutboxTaskQueryPort;
import com.lrj.trade.core.ReconciliationCaseService;
import com.lrj.trade.core.ReconciliationOperationService;
import com.lrj.trade.core.query.AdminNumberedPage;
import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.core.query.OperationAuditQuery;
import com.lrj.trade.refund.AdminRefundQueryService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计和运行队列保留原读权限及安全投影，只扩展人工页码定位。 */
@RestController
@AdminRead
@RequestMapping("/api/v1/trade/admin/numbered-operations")
public class NumberedOperationalListsController {
    private static final String DEFAULT_QUEUE_LIMIT = "50";
    private final OutboxOperationsService outbox;
    private final OutboxTaskQueryPort tasks;
    private final ReconciliationCaseService cases;
    private final ReconciliationOperationService reconciliation;
    private final OperationAuditQuery auditQuery;
    private final AuditWriter audit;
    private final AdminRefundQueryService refunds;

    /** 各页仍通过已公开的领域查询及留痕服务读取。 */
    public NumberedOperationalListsController(OutboxOperationsService outbox, OutboxTaskQueryPort tasks,
            ReconciliationCaseService cases, ReconciliationOperationService reconciliation,
            OperationAuditQuery auditQuery, AuditWriter audit, AdminRefundQueryService refunds) {
        this.outbox = outbox;
        this.tasks = tasks;
        this.cases = cases;
        this.reconciliation = reconciliation;
        this.auditQuery = auditQuery;
        this.audit = audit;
        this.refunds = refunds;
    }

    /** 显式允许的队列和审计列表，不接受调用方提供的表、排序或租户。 */
    @GetMapping("/{resource}")
    public AdminNumberedPage<?> page(@AuthenticationPrincipal Jwt jwt, @PathVariable String resource,
            @RequestParam MultiValueMap<String, String> params) {
        long tenant = tenant(jwt);
        return switch (resource) {
            case "operation-audits" -> {
                var request = AdminQueryParameters.numbered(params,
                        input -> AdminQuery.parse(input, AdminQuery.fields("resourceType", "resourceRef"), true));
                yield auditQuery.numberedPage(tenant, request.query(), request.page());
            }
            case "outbox-publications" -> {
                var request = queue(params, false, "REVIEW");
                yield outbox.numberedPage(tenant, state(request), request.query().limit(), request.page());
            }
            case "outbox-tasks" -> {
                var request = queue(params, false, "REVIEW");
                yield tasks.numberedPage(tenant, state(request), request.query().limit(), request.page());
            }
            case "reconciliation-cases" -> {
                var request = queue(params, true, "OPEN");
                yield cases.numberedPage(tenant, state(request), request.query().limit(), request.page(), number(request));
            }
            case "reconciliation-operations" -> {
                var request = queue(params, true, "REVIEW");
                yield reconciliation.numberedPage(tenant, state(request), request.query().limit(), request.page(), number(request));
            }
            default -> throw AdminQuery.invalid();
        };
    }

    /** 退款审计先验证父退款，旧号及可读编号走相同所属校验与查阅留痕。 */
    @GetMapping("/refund-audits/{refundNo}")
    public AdminNumberedPage<?> refundAudit(@AuthenticationPrincipal Jwt jwt, @PathVariable String refundNo,
            @RequestParam MultiValueMap<String, String> params) {
        AdminQuery.text(refundNo, 64);
        var request = AdminQueryParameters.numbered(params, input -> {
            input.putIfAbsent("limit", List.of(DEFAULT_QUEUE_LIMIT));
            return AdminQuery.parse(input, Map.of(), false);
        });
        long tenant = tenant(jwt);
        var refund = refunds.read(tenant, refundNo);
        var actor = new Actor(tenant, jwt.getIssuer().toString(), jwt.getSubject());
        return audit.numberedRefundHistory(actor, refund.refundNo(), request.query().limit(), request.page());
    }

    private static AdminQueryParameters.Numbered queue(MultiValueMap<String, String> params,
            boolean exactNumber, String defaultState) {
        return AdminQueryParameters.numbered(params, input -> {
            input.putIfAbsent("limit", List.of(DEFAULT_QUEUE_LIMIT));
            input.putIfAbsent("state", List.of(defaultState));
            return AdminQuery.parse(input, exactNumber ? AdminQuery.fields("state", "number") : AdminQuery.fields("state"), false);
        });
    }

    private static String state(AdminQueryParameters.Numbered request) { return (String) request.query().filters().get("state"); }
    private static String number(AdminQueryParameters.Numbered request) { return (String) request.query().filters().get("number"); }
    private static long tenant(Jwt jwt) { return AdminQuery.number(jwt.getClaimAsString("tenant_id"), false); }
}
