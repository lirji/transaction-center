package com.lrj.trade.server;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 从真实application.yml验证环境变量映射，不仅检查Compose字符串。 */
class DeploymentConfigurationTest {
    private ApplicationContextRunner runner() {
        var yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application.yml"));
        return new ApplicationContextRunner().withInitializer(c->c.getEnvironment().getPropertySources()
            .addLast(new PropertiesPropertySource("application-yaml",yaml.getObject())))
            .withBean(Clock.class,Clock::systemUTC).withUserConfiguration(OrderAttributionConfiguration.class)
            .withPropertyValues("trade.security.audience=transaction-center");
    }
    @Test void optionalFeaturesAreDisabledByDefault() {
        runner().run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(OrderAttributionVerifier.class);
            assertThat(c.getEnvironment().getProperty("trade.admin-evidence.enabled")).isEqualTo("false");
            assertThat(c.getEnvironment().getProperty("trade.automatic-admission.enabled")).isEqualTo("false");});
    }
    @Test void attributionEnvironmentSwitchRequiresTrustedConfiguration() {
        runner().withPropertyValues("TRADE_ORDER_ATTRIBUTION_ENABLED=true").run(c->assertThat(c).hasFailed());
        runner().withPropertyValues("TRADE_ORDER_ATTRIBUTION_ENABLED=true","TRADE_ORDER_ATTRIBUTION_ISSUER=https://issuer.test",
            "TRADE_ORDER_ATTRIBUTION_JWKS=https://issuer.test/jwks").run(c->{assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(OrderAttributionVerifier.class);});
    }
    @Test void invalidQueryAndUnmappedDiskSettingsFailClosed() {
        assertThrows(IllegalArgumentException.class,()->new AdminReadConfiguration(0,1,1));
        assertThrows(IllegalArgumentException.class,()->new AdminReadConfiguration(1,-1,1));
        assertThrows(IllegalArgumentException.class,()->new AdminReadConfiguration(1,1,0));
        assertThrows(IllegalArgumentException.class,()->new DatabaseDiskSpace("",""));
        assertThrows(IllegalArgumentException.class,()->new DatabaseDiskSpace("relative","store"));
    }
}
