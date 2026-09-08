package com.lrj.trade.server;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 默认不开启营销授权；固定HTTPS来源不能从请求中的jku或x5u推导。 */
@Configuration
public class OrderAttributionConfiguration {
    /** 显式启用才装配验证器，缺少生产信任配置时拒绝启动。 */
    @Bean
    @ConditionalOnProperty(name="trade.order-attribution.enabled",havingValue="true")
    public OrderAttributionVerifier orderAttributionVerifier(
        @Value("${trade.order-attribution.issuer:}") String issuer,
        @Value("${trade.order-attribution.jwks:}") String jwks,
        @Value("${trade.security.audience}") String accessAudience,Clock clock) {
        if(OrderAttributionVerifier.AUDIENCE.equals(accessAudience)) throw new IllegalArgumentException("归因必须使用专属接收方");
        var uri=java.net.URI.create(jwks);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("归因JWKS必须使用固定HTTPS地址");
        return OrderAttributionVerifier.create(issuer,jwks,clock);
    }
}
