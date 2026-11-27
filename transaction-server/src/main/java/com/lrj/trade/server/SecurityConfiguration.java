package com.lrj.trade.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/** 默认拒绝匿名业务访问；缺少正式身份配置时不允许以伪造请求头降级。 */
@Configuration
public class SecurityConfiguration {
    /** 校验签名、签发方、时间与接收方，防止将其他应用的合法令牌挪用。 */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${trade.security.issuer}") String issuer,
                                 @Value("${trade.security.jwk-set-uri}") String jwks,
                                 @Value("${trade.security.audience}") String audience) {
        NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> audienceValidator=jwt -> jwt.getAudience().contains(audience) && jwt.getExpiresAt()!=null
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","接收方不匹配或缺少过期时间",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),audienceValidator,SecurityConfiguration::validateIdentity));
        return decoder;
    }
    /** 统一在认证边界拒绝缺失、数值型、非规范文本或超出数据库范围的租户，避免各控制器宽松转换。 */
    private static OAuth2TokenValidatorResult validateIdentity(Jwt jwt) {
        Object tenant=jwt.getClaims().get("tenant_id");
        boolean valid=tenant instanceof String text && text.matches("[1-9][0-9]{0,18}");
        if(valid) {
            try { Long.parseLong((String)tenant); }
            catch(NumberFormatException overflow) { valid=false; }
        }
        String subject=jwt.getSubject();
        if(valid && subject!=null && !subject.isBlank() && subject.length()<=255)
            return OAuth2TokenValidatorResult.success();
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","令牌缺少有效租户或主体声明",null));
    }
    /** 仅健康探针匿名开放，管理操作需要显式作用域；无Cookie认证因此禁用CSRF。 */
    @Bean
    public SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf->csrf.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/actuator/prometheus").hasAuthority("SCOPE_trade.metrics.read")
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                    com.lrj.trade.server.checkout.CheckoutPilotQuoteController.PATH).hasAuthority("SCOPE_trade.checkout-pilot.prepare")
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                    com.lrj.trade.server.checkout.CheckoutPilotPriceController.PATH).hasAuthority("SCOPE_trade.checkout-pilot.freeze")
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                    com.lrj.trade.server.checkout.CheckoutFundingReceiptController.PATH).hasAuthority("SCOPE_trade.checkout-pilot.funding")
                .requestMatchers(org.springframework.http.HttpMethod.GET,
                    com.lrj.trade.server.checkout.CheckoutPilotOrderController.EVIDENCE_PATH).hasAuthority("SCOPE_trade.checkout-pilot.order-evidence")
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/internal/v1/reconciliation/reports").hasAuthority("SCOPE_trade.reconciliation.report")
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/v1/reconciliation/money-facts","/internal/v1/reconciliation/cases/*").hasAuthority("SCOPE_trade.reconciliation.read")
                .requestMatchers("/internal/v1/first-order-referral-evidence:query","/internal/v1/customer-eligibility:query","/internal/v1/order-referral-evidence:query","/internal/v1/orders/*/referral-evidence").hasAuthority("SCOPE_trade.evidence.read")
                .requestMatchers("/api/v1/trade/integrations/refund-approvals").hasAuthority("SCOPE_trade.refund.approval")
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/trade/admin/reconciliation-cases/*:query")
                    .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin"),
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.reconciliation.query")))
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/trade/admin/outbox-publications/*:replay")
                    .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin"),
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.events.replay")))
                .requestMatchers(org.springframework.http.HttpMethod.PATCH,"/api/v1/trade/admin/admission-policy")
                    .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin"),
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admission.write")))
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/internal/v1/orders/*:complete").hasAuthority("SCOPE_trade.fulfillment.complete")
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/trade/admin/orders/*/fulfillments")
                    .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin"),
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.fulfillment.write")))
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/trade/admin/returns","/api/v1/trade/admin/returns/*:inspect")
                    .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin"),
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.return.write")))
                // 精确只读权限规则必须先于admin通配规则，管理身份不能隐式取得证据或租约权限。
                .requestMatchers("/api/v1/trade/admin/orders/*/referral-evidence","/api/v1/trade/admin/orders/*/operation-evidence",
                        "/api/v1/trade/admin/operations/linked-checkouts")
                    .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin"),
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin.evidence.read")))
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/trade/admin/members/*/eligibility:query")
                    .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin"),
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin.evidence.read")))
                .requestMatchers("/api/v1/trade/admin/jobs","/api/v1/trade/admin/jobs/*",
                        "/api/v1/trade/admin/numbered-pages/jobs")
                    .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin"),
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin.jobs.read")))
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/trade/admin/refund-audit-archives/*/restore")
                    .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin"),
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin.audit.archive.restore")))
                .requestMatchers("/api/v1/trade/admin/refund-audit-archives/*")
                    .access(org.springframework.security.authorization.AuthorizationManagers.allOf(
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin"),
                        org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority("SCOPE_trade.admin.audit.archive.read")))
                .requestMatchers("/api/v1/trade/admin/**").hasAuthority("SCOPE_trade.admin")
                .requestMatchers("/api/v1/trade/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o->o.jwt(j->{})).build();
    }
}
