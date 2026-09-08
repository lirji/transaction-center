package com.lrj.trade.server;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.refund.RefundApprovalReceiptService;
import com.lrj.trade.refund.model.VerifiedApprovalReceipt;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证默认关闭和固定验签受理调用链；密码学与数据库事务另有真实集成测试。 */
class RefundApprovalIngressTest {
    @Test void disabledByDefaultNeverTouchesReceipts() {
        var receipts=mock(RefundApprovalReceiptService.class);
        new ApplicationContextRunner().withUserConfiguration(RefundApprovalConfiguration.class,RefundApprovalIngress.class)
            .withBean(RefundApprovalReceiptService.class,()->receipts)
            .withBean(RefundApprovalRejectionAudit.class,()->mock(RefundApprovalRejectionAudit.class))
            .run(context->{
                assertNull(context.getStartupFailure());
                assertTrue(context.getBeansOfType(RefundApprovalVerifier.class).isEmpty());
                assertEquals("REFUND_APPROVAL_DISABLED",assertThrows(TradeException.class,()->context.getBean(RefundApprovalIngress.class)
                    .accept(new Actor(1,"https://identity.test","workflow"),"untrusted")).code());
                verifyNoInteractions(receipts);
                // 使用真实关闭配置的Ingress及MVC映射验证503；鉴权过滤链另由Foundation HTTP测试覆盖。
                var controller=new TradeController(null,null,null,null,null,null,null,null,null,null,null,null,null,context.getBean(RefundApprovalIngress.class));
                var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("disabled-test").header("alg","RS256")
                    .issuer("https://identity.test").subject("workflow").claim("tenant_id","1").build();
                var security=org.springframework.security.core.context.SecurityContextHolder.getContext();
                security.setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
                try {
                    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new Errors())
                        .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()).build();
                    var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/trade/integrations/refund-approvals")
                        .contentType("application/json").content("{\"signedReceipt\":\"untrusted\"}")).andReturn();
                    assertEquals(503,result.getResponse().getStatus()); verifyNoInteractions(receipts);
                } catch(Exception failure) { throw new AssertionError(failure); }
                finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            });
    }
    @Test void explicitEnableNeedsCompleteHttpsAndDedicatedAudience() {
        var config=new RefundApprovalConfiguration(); var clock=Clock.systemUTC();
        assertThrows(IllegalArgumentException.class,()->config.refundApprovalVerifier("https://flow.test","http://flow.test/keys",
            "refund","workflow","https://identity.test","trade",clock));
        assertThrows(IllegalArgumentException.class,()->config.refundApprovalVerifier("https://flow.test","https://flow.test/keys",
            "trade","workflow","https://identity.test","trade",clock));
        assertThrows(TradeException.class,()->config.refundApprovalVerifier("https://flow.test","https://flow.test/keys",
            "refund","","https://identity.test","trade",clock));
    }
    @Test void onlyVerifiedResultCanReachDurableAcceptorAndFailureIsNotAcknowledged() {
        var receipts=mock(RefundApprovalReceiptService.class); var verifier=mock(RefundApprovalVerifier.class);
        @SuppressWarnings("unchecked") ObjectProvider<RefundApprovalVerifier> providers=mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(verifier);
        var audit=mock(RefundApprovalRejectionAudit.class);
        when(audit.record(any(),anyString(),anyString())).thenReturn("ARJ-test");
        var ingress=new RefundApprovalIngress(receipts,providers,audit); var actor=new Actor(1,"https://identity.test","workflow");
        var proof=new VerifiedApprovalReceipt("https://flow.test","workflow","one",actor,"RF-one","RA-one",1,true,"evidence",
            Instant.EPOCH,Instant.EPOCH.plusSeconds(120),"signed");
        when(verifier.verify(actor,"signed")).thenReturn(proof); when(receipts.accept(proof)).thenReturn("RAP-one");
        assertEquals("RAP-one",ingress.accept(actor,"signed"));
        var order=inOrder(verifier,receipts); order.verify(verifier).verify(actor,"signed"); order.verify(receipts).accept(proof);
        when(verifier.verify(actor,"bad")).thenThrow(new TradeException("REFUND_APPROVAL_UNTRUSTED","拒绝"));
        assertThrows(ApprovalRejection.class,()->ingress.accept(actor,"bad")); verify(receipts,times(1)).accept(any());
        when(receipts.accept(proof)).thenThrow(new TradeException("CALLBACK_ENCRYPTION_UNAVAILABLE","缺密钥"));
        assertThrows(ApprovalRejection.class,()->ingress.accept(actor,"signed"));
        when(audit.record(any(),anyString(),anyString())).thenThrow(new TradeException("REFUND_APPROVAL_AUDIT_UNAVAILABLE","不可用"));
        var unavailable=assertThrows(TradeException.class,()->ingress.accept(actor,"bad"));
        assertEquals(503,new Errors().business(unavailable).getStatusCode().value());
        assertTrue(new Errors().business(unavailable).getBody().retryable());
    }
}
