package com.lrj.trade.server;
import com.lrj.trade.core.*;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 后台发布摘要与受控重放入口，租户/主体只能从已验证JWT取得。 */
@RestController
@RequestMapping("/api/v1/trade/admin/outbox-publications")
public class OutboxOperationsController {
    private final OutboxOperationsService operations;
    private final OutboxTaskQueryPort tasks;
    /** 控制器不访问Mapper或修改消息，权限在过滤链独立约束。 */
    public OutboxOperationsController(OutboxOperationsService operations,OutboxTaskQueryPort tasks) { this.operations=operations;this.tasks=tasks; }
    /** 有界分页只展示发布元数据，不将原事件payload返回后台列表。 */
    @GetMapping("")
    public List<OutboxPublication> page(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="REVIEW") String state,
        @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit) {
        return operations.page(actor(jwt).tenantId(),state,after,limit);
    }
    /** 单独展示坏键等无事件任务，原键只显示规范事件编号或摘要。 */
    @GetMapping("/tasks")
    public List<OutboxTaskQueryPort.View> tasks(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="REVIEW") String state,
        @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit) {
        return tasks.page(actor(jwt).tenantId(),state,after,limit);
    }
    /** 后台身份还须独立重放scope，原键原内容永久返回首次结果。 */
    @PostMapping("/{eventId}:replay")
    public OutboxOperationsService.Result replay(@AuthenticationPrincipal Jwt jwt,@PathVariable String eventId,
        @RequestHeader("Idempotency-Key") String key,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody ReplayInput input) {
        return AdminVersion.execute(match,input.expectedVersion(),()->operations.replay(actor(jwt),key,eventId,input.expectedVersion(),input.reason(),input.ticketRef()));
    }
    private Actor actor(Jwt jwt) {
        try { return new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")),jwt.getIssuer().toString(),jwt.getSubject()); }
        catch(RuntimeException invalid) { throw new TradeException("INVALID_IDENTITY","令牌缺少有效租户或主体声明"); }
    }
    /** 原因与工单引用只用于审计，不是改变资金事实的授权证明。 */
    public record ReplayInput(@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Size(max=255) String reason,
        @NotBlank @Size(max=128) String ticketRef) {}
}
