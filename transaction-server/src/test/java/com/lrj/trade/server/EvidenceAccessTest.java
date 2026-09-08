package com.lrj.trade.server;

import com.lrj.trade.core.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** 默认关闭也通过真实资源服务器过滤链验证，不以standalone MVC替代鉴权。 */
class EvidenceAccessTest {
    @Test void disabledHttpReturns503AfterFullSecurityChainWithoutReadingFacts() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of(
                "trade.security.issuer","https://identity.test","trade.security.jwk-set-uri","https://identity.test/keys","trade.security.audience","transaction-center")));
            context.register(Config.class); context.refresh();
            var mvc=webAppContextSetup(context).apply(springSecurity()).build();
            var result=mvc.perform(get("/internal/v1/orders/1/referral-evidence").header("Authorization","Bearer service")).andReturn();
            assertEquals(503,result.getResponse().getStatus()); assertTrue(result.getResponse().getContentAsString().contains("EVIDENCE_DISABLED"));
            verifyNoInteractions(context.getBean(OrderReferralEvidenceService.class),context.getBean(CustomerEligibilityService.class));
            assertEquals(401,mvc.perform(get("/internal/v1/orders/1/referral-evidence")).andReturn().getResponse().getStatus());
        }
    }
    @Test void explicitEnableRequiresNonemptyWellFormedServiceIdentitySet() {
        assertThrows(IllegalArgumentException.class,()->new EvidenceAccess(true,""));
        assertThrows(IllegalArgumentException.class,()->new EvidenceAccess(true,"not-a-digest"));
        assertThrows(IllegalArgumentException.class,()->new EvidenceAccess(true,"a".repeat(64)+","));
    }
    @Test void identityIncludesIssuerAndSubjectAndNeverAcceptsAdminByDefault() {
        var allowed=new Actor(1,"https://identity.test","service"); var access=new EvidenceAccess(true,allowed.key());
        assertDoesNotThrow(()->access.require(allowed));
        assertEquals("EVIDENCE_UNTRUSTED",assertThrows(TradeException.class,()->access.require(new Actor(1,"https://other.test","service"))).code());
        assertEquals("EVIDENCE_UNTRUSTED",assertThrows(TradeException.class,()->access.require(new Actor(1,"https://identity.test","admin"))).code());
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class,EvidenceController.class,EvidenceAccess.class,Errors.class})
    static class Config {
        @Bean @Primary JwtDecoder testDecoder() {
            var decoder=mock(JwtDecoder.class);
            when(decoder.decode("service")).thenReturn(Jwt.withTokenValue("service").header("alg","RS256").issuer("https://identity.test")
                .subject("service").claim("tenant_id","1").claim("scope","trade.evidence.read")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());
            return decoder;
        }
        @Bean OrderReferralEvidenceService orders() { return mock(OrderReferralEvidenceService.class); }
        @Bean CustomerEligibilityService customers() { return mock(CustomerEligibilityService.class); }
    }
}
