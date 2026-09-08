package com.lrj.trade.server;
import com.lrj.trade.core.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 管理员使用双scope及强版本条件修改本租户准入策略。 */
@RestController
@RequestMapping("/api/v1/trade/admin/admission-policy")
public class AdmissionController {
    private final AdmissionService admission;
    /** 控制端不访问订单或资金表，保持锁序单向。 */
    public AdmissionController(AdmissionService admission) {this.admission=admission;}
    /** ETag绑定持久版本，缺省策略的版本为零。 */
    @GetMapping("")
    public ResponseEntity<AdmissionPolicy> read(@AuthenticationPrincipal Jwt jwt) {return response(admission.read(actor(jwt).tenantId()));}
    /** 幂等重放返回原更新版本；条件缺失拒绝而不是无条件覆盖。 */
    @PatchMapping("")
    public ResponseEntity<AdmissionPolicy> update(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody Input input) {
        long version=AdminVersion.parse(match);
        return response(admission.update(actor(jwt),key,version,input.orderEnabled(),input.paymentEnabled(),input.reason()));
    }
    private ResponseEntity<AdmissionPolicy> response(AdmissionPolicy policy) {return ResponseEntity.ok().eTag("\""+policy.version()+"\"").body(policy);}
    private Actor actor(Jwt jwt) {try {return new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")),jwt.getIssuer().toString(),jwt.getSubject());}catch(RuntimeException invalid) {throw new TradeException("INVALID_IDENTITY","令牌缺少有效身份");}}
    /** 两个开关都必须显式提交，防止缺省false意外关闭业务。 */
    public record Input(@NotNull Boolean orderEnabled,@NotNull Boolean paymentEnabled,@NotBlank @Size(max=255) String reason) {}
}
