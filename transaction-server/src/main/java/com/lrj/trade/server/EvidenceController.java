package com.lrj.trade.server;
import com.lrj.trade.core.*;
import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 正式交易原生只读证据入口；权威营销身份映射与C03主体补查另由后续适配接入。 */
@RestController
@RequestMapping("/internal/v1")
public class EvidenceController {
    private final EvidenceAccess access;
    private final OrderReferralEvidenceService orders;
    private final CustomerEligibilityService customers;
    /** 缺省入口关闭；控制器不接受调用方自报租户、金额或首单结果。 */
    public EvidenceController(EvidenceAccess access,OrderReferralEvidenceService orders,CustomerEligibilityService customers) {
        this.access=access; this.orders=orders; this.customers=customers;
    }
    /** 返回同一主库快照的单订单证据，其他租户统一不存在。 */
    @GetMapping("/orders/{orderId}/referral-evidence")
    public OrderReferralEvidenceService.Evidence order(@AuthenticationPrincipal Jwt jwt,@PathVariable String orderId) {
        var actor=actor(jwt); var result=orders.query(actor.tenantId(),List.of(identifier(orderId))).getFirst();
        if(result.evidence()==null) throw new TradeException("NOT_FOUND","订单不存在");
        return result.evidence();
    }
    /** 交易原生批量形状，最多100个ID；不冒充尚未接入canonicalSubject的首单补查。 */
    @PostMapping("/order-referral-evidence:query")
    public List<OrderReferralEvidenceService.Result> orders(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody OrdersInput input) {
        var actor=actor(jwt); return orders.query(actor.tenantId(),input.orderIds().stream().map(this::identifier).toList());
    }
    /** 交易原生memberId策略查询，不接受未经权威映射的外部主体作为会员编号。 */
    @PostMapping("/customer-eligibility:query")
    public CustomerEligibilityService.Result customer(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody CustomerInput input) {
        var actor=actor(jwt); return customers.query(actor.tenantId(),identifier(input.memberId()),input.policyVersion(),input.campaignStartAt(),input.evaluatedAt());
    }
    /** 原生会员首单补查独立路径，保持现有批量形状不变；后续C03按明确映射接入。 */
    @PostMapping("/first-order-referral-evidence:query")
    public OrderReferralEvidenceService.FirstResult first(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody FirstInput input) {
        var actor=actor(jwt); return orders.first(actor.tenantId(),identifier(input.memberId()),input.from(),input.to(),input.firstOrderPolicyVersion());
    }
    /** 期间采用本地首次确认时间，不是渠道时间或历史金额截止时间。 */
    public record FirstInput(@NotBlank String memberId,@NotNull Instant from,@NotNull Instant to,@NotBlank String firstOrderPolicyVersion) {}
    private Actor actor(Jwt jwt) {
        Actor actor;
        try {
            if(jwt.getIssuer()==null || jwt.getSubject()==null) throw new IllegalArgumentException();
            actor=new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")),jwt.getIssuer().toString(),jwt.getSubject());
        } catch(RuntimeException invalid) { throw new TradeException("INVALID_IDENTITY","令牌缺少有效租户或主体声明"); }
        access.require(actor); return actor;
    }
    private long identifier(String value) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw new TradeException("INVALID_ARGUMENT","资源编号无效");
        try { return Long.parseLong(value); } catch(NumberFormatException invalid) { throw new TradeException("INVALID_ARGUMENT","资源编号超限"); }
    }
    /** 有界原生批量查询输入；额外字段由严格JSON配置拒绝。 */
    public record OrdersInput(@NotNull @Size(min=1,max=100) List<@NotBlank String> orderIds) {}
    /** 当前评估可省略evaluatedAt；活动注册策略必须提供campaignStartAt。 */
    public record CustomerInput(@NotBlank String memberId,@NotBlank String policyVersion,Instant campaignStartAt,Instant evaluatedAt) {}
}
