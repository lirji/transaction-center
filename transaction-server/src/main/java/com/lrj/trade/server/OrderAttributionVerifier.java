package com.lrj.trade.server;

import com.lrj.trade.core.*;
import com.lrj.trade.order.OrderAttributionVerifierPort;
import com.lrj.trade.order.model.VerifiedOrderAttribution;
import java.time.Clock;
import java.util.Objects;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/** 独立营销签名边界；只认证归因授权，不以分析点击或客户端声明替代奖励资格。 */
public final class OrderAttributionVerifier implements OrderAttributionVerifierPort {
    public static final String AUDIENCE="transaction-center-order-attribution";
    private final JwtDecoder decoder;
    private final Clock clock;
    private OrderAttributionVerifier(JwtDecoder decoder,Clock clock) { this.decoder=decoder; this.clock=clock; }

    /** 固定配置取钥，RS256且连接/读取均有界；测试可使用本机HTTP，生产配置层强制HTTPS。 */
    public static OrderAttributionVerifier create(String issuer,String jwks,Clock clock) {
        Checks.text(issuer,255); Checks.text(jwks,1000);
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(2000);
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(new RestTemplate(factory)).build();
        var converter=MappedJwtClaimSetConverter.withDefaults(java.util.Map.of());
        decoder.setClaimSetConverter(raw->{
            // Nimbus默认可能从exp合成缺失iat；必须先检查原始声明再做标准时间转换。
            if(raw.get("iat")==null || raw.get("exp")==null) throw invalid();
            for(String name:java.util.List.of("iss","jti","tenant_id","actor_key","member_id","quote_id","referral_ref","campaign_id","token_kind"))
                if(!(raw.get(name) instanceof String)) throw invalid();
            return converter.convert(raw);
        });
        OAuth2TokenValidator<Jwt> audience=jwt->jwt.getAudience().equals(java.util.List.of(AUDIENCE))
            ?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        // 时间只用注入Clock及下方精确边界校验，禁止默认宽限期放过到期令牌。
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(issuer),audience));
        return new OrderAttributionVerifier(decoder,Objects.requireNonNull(clock));
    }

    /** 显式事务guard也保护直接调用，不能仅依赖代理注解或调用者约定。 */
    @Override public VerifiedOrderAttribution verify(Actor actor,long member,long quote,String compact) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new TradeException("ORDER_ATTRIBUTION_TRANSACTION","归因验签不能在业务事务内执行");
        if(compact==null || compact.isBlank() || compact.length()>16384) throw invalid();
        try {
            var jwt=decoder.decode(compact);
            if(!"ORDER_REFERRAL_ATTRIBUTION_V1".equals(text(jwt,"token_kind",64)) || number(jwt,"schema_version")!=1) throw invalid();
            var claim=new VerifiedOrderAttribution(text(jwt,"iss",255),text(jwt,"jti",64),text(jwt,"tenant_id",20),
                text(jwt,"actor_key",64),text(jwt,"member_id",20),text(jwt,"quote_id",20),text(jwt,"referral_ref",64),
                text(jwt,"campaign_id",64),number(jwt,"definition_version"),Checks.hash(compact),jwt.getIssuedAt(),jwt.getExpiresAt(),jwt.getNotBefore());
            claim.requireUsable(actor,member,quote,clock.instant());
            return claim;
        } catch(RuntimeException rejected) {
            // 不把原令牌、供应商响应、内部地址或异常cause泄露到日志及HTTP。
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
        long result=((Number)value).longValue();
        if(result<1 || result>9007199254740991L) throw invalid();
        return result;
    }
    private static TradeException invalid() { return new TradeException("ORDER_ATTRIBUTION_UNTRUSTED","订单归因令牌无效"); }
}
