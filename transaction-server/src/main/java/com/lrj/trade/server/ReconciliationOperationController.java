package com.lrj.trade.server;
import com.lrj.trade.core.*;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 管理员仅申请固定查单动作，独立scope和默认关闭开关控制执行入口。 */
@RestController
@RequestMapping("")
public class ReconciliationOperationController {
    private final ReconciliationOperationService operations;private final boolean enabled;
    /** 与独立Worker开关一致，普通管理权限本身不能申请渠道查询。 */
    public ReconciliationOperationController(ReconciliationOperationService operations,@Value("${trade.reconciliation.query-enabled:false}") boolean enabled) {this.operations=operations;this.enabled=enabled;}
    /** 永久请求键绑定版本原因工单；返回首次排队结果，不表示资金已处理。 */
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    @PostMapping("/api/v1/trade/admin/reconciliation-cases/{caseNo}:query")
    public ReconciliationOperationService.Result query(@AuthenticationPrincipal Jwt jwt,@PathVariable String caseNo,@RequestHeader("Idempotency-Key") String key,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody Input input) {
        if(!enabled) throw new TradeException("RECONCILIATION_DISABLED","对账查询执行尚未启用");
        return AdminVersion.execute(match,input.expectedVersion(),()->operations.request(actor(jwt),key,caseNo,input.expectedVersion(),input.action(),input.reason(),input.ticketRef()));
    }
    /** 执行状态从持久操作表读取，区别于永久回执的首次QUEUED。 */
    @GetMapping("/api/v1/trade/admin/reconciliation-operations")
    public List<ReconciliationOperationService.View> page(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="REVIEW") String state,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String number) {return operations.page(actor(jwt).tenantId(),state,after,limit,number); }
    private Actor actor(Jwt jwt) {try {return new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")),jwt.getIssuer().toString(),jwt.getSubject());}catch(RuntimeException invalid) {throw new TradeException("INVALID_IDENTITY","令牌缺少有效身份");}}
    /** 仅两个动作，原因和工单不是资金变更授权。 */
    public record Input(@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Pattern(regexp="QUERY_PAYMENT|QUERY_REFUND") String action,@NotBlank @Size(max=255) String reason,@NotBlank @Size(max=128) String ticketRef) {}
}
