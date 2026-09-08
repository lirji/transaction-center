package com.lrj.trade.server;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.Checks;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.refund.model.VerifiedApprovalReceipt;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

/** 独立验签审批回执；仅在显式开启且来源完整时装配，HTTP必须在验证后调用耐久受理。 */
public final class RefundApprovalVerifier {
    private final JwtDecoder decoder;
    private final String accessIssuer;
    private final String serviceSubject;
    private final String approverIssuer;
    private final Clock clock;

    private RefundApprovalVerifier(JwtDecoder decoder,String accessIssuer,String serviceSubject,String approverIssuer,Clock clock) {
        this.decoder=decoder; this.accessIssuer=accessIssuer; this.serviceSubject=serviceSubject;
        this.approverIssuer=approverIssuer; this.clock=clock;
    }

    /** 固定外部配置而非报文中的JWKS地址；审批回执使用专属audience，不能挪用普通登录令牌。 */
    public static RefundApprovalVerifier create(String receiptIssuer,String jwks,String audience,String accessIssuer,
        String serviceSubject,String approverIssuer,Clock clock) {
        Checks.text(receiptIssuer,255); Checks.text(jwks,1000); Checks.text(audience,255);
        Checks.text(accessIssuer,255); Checks.text(serviceSubject,255); Checks.text(approverIssuer,255);
        NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> receiver=jwt->jwt.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        JwtTimestampValidator timestamps=new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamps.setClock(Objects.requireNonNull(clock));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(receiptIssuer),timestamps,receiver));
        return new RefundApprovalVerifier(decoder,accessIssuer,serviceSubject,approverIssuer,Objects.requireNonNull(clock));
    }

    /** 调用者必须先通过访问令牌认证与专用作用域检查；签名回执再绑定其服务主体及租户。 */
    public VerifiedApprovalReceipt verify(Actor caller,String compact) {
        if(caller==null || !accessIssuer.equals(caller.issuer()) || !serviceSubject.equals(caller.subject()))
            throw invalid();
        if(compact==null || compact.length()>16384 || compact.isBlank()) throw invalid();
        try {
            Jwt jwt=decoder.decode(compact);
            if(!serviceSubject.equals(jwt.getSubject()) || !"REFUND_APPROVAL_DECISION_V1".equals(text(jwt,"receipt_kind",64))
                || number(jwt,"schema_version")!=1 || !Long.toString(caller.tenantId()).equals(text(jwt,"tenant_id",20))) throw invalid();
            var issued=jwt.getIssuedAt(); var expires=jwt.getExpiresAt(); var now=clock.instant();
            // 限制可重放窗口；最终幂等还必须由数据库永久事件键和原审批版本保证。
            if(issued==null || expires==null || !expires.isAfter(issued) || !now.isBefore(expires)
                || issued.isAfter(now.plusSeconds(30)) || Duration.between(issued,expires).compareTo(Duration.ofMinutes(5))>0) throw invalid();
            String issuer=text(jwt,"approver_issuer",255),subject=text(jwt,"approver_subject",255);
            if(!approverIssuer.equals(issuer) || (accessIssuer.equals(issuer) && serviceSubject.equals(subject))) throw invalid();
            String decision=text(jwt,"decision",16);
            if(!decision.equals("APPROVE") && !decision.equals("REJECT")) throw invalid();
            return new VerifiedApprovalReceipt(jwt.getIssuer().toString(),jwt.getSubject(),text(jwt,"jti",64),new Actor(caller.tenantId(),issuer,subject),
                text(jwt,"refund_no",64),text(jwt,"approval_ref",64),number(jwt,"decision_version"),decision.equals("APPROVE"),
                text(jwt,"evidence_ref",255),issued,expires,compact);
        } catch(RuntimeException rejected) {
            // 验签异常不能携带原JWT、供应商响应或凭据地址进入HTTP错误与日志。
            throw invalid();
        }
    }

    private static String text(Jwt jwt,String name,int limit) {
        Object value=jwt.getClaims().get(name);
        if(!(value instanceof String text) || text.isBlank() || text.length()>limit) throw invalid();
        return text;
    }
    private static long number(Jwt jwt,String name) {
        Object value=jwt.getClaims().get(name);
        if(!(value instanceof Long) && !(value instanceof Integer)) throw invalid();
        long number=((Number)value).longValue();
        if(number<1 || number>9007199254740991L) throw invalid();
        return number;
    }
    private static TradeException invalid() { return new TradeException("REFUND_APPROVAL_UNTRUSTED","审批回执认证失败"); }
}
