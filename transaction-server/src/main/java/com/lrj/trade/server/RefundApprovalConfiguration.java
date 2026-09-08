package com.lrj.trade.server;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 审批来源未正式配置时不装配验证器；禁止用通用后台令牌或默认身份降级。 */
@Configuration
public class RefundApprovalConfiguration {
    /** 显式启用才要求完整来源配置，回执audience必须与普通访问令牌区分。 */
    @Bean
    @ConditionalOnProperty(name="trade.refund-approval.enabled",havingValue="true")
    public RefundApprovalVerifier refundApprovalVerifier(
        @Value("${trade.refund-approval.issuer:}") String issuer,
        @Value("${trade.refund-approval.jwks:}") String jwks,
        @Value("${trade.refund-approval.audience:}") String audience,
        @Value("${trade.refund-approval.service-subject:}") String subject,
        @Value("${trade.security.issuer}") String accessIssuer,
        @Value("${trade.security.audience}") String accessAudience,Clock clock) {
        if(audience.equals(accessAudience)) throw new IllegalArgumentException("审批回执必须使用专属接收方");
        var keys=java.net.URI.create(jwks);
        if(!"https".equalsIgnoreCase(keys.getScheme()) || keys.getHost()==null || keys.getUserInfo()!=null || keys.getFragment()!=null)
            throw new IllegalArgumentException("审批JWKS必须使用固定HTTPS地址");
        return RefundApprovalVerifier.create(issuer,jwks,audience,accessIssuer,subject,accessIssuer,clock);
    }
}
