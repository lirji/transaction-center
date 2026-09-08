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
            JwtValidators.createDefaultWithIssuer(issuer),audienceValidator));
        return decoder;
    }
    /** 仅健康探针匿名开放，管理操作需要显式作用域；无Cookie认证因此禁用CSRF。 */
    @Bean
    public SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf->csrf.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/actuator/prometheus").hasAuthority("SCOPE_trade.metrics.read")
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
                .requestMatchers("/api/v1/trade/admin/**").hasAuthority("SCOPE_trade.admin")
                .requestMatchers("/api/v1/trade/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o->o.jwt(j->{})).build();
    }
}
