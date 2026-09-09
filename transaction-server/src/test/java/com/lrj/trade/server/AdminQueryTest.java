package com.lrj.trade.server;
import com.lrj.trade.core.*;
import com.lrj.trade.core.query.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;

/** 无数据库单测覆盖参数边界、配额释放和诊断入口关闭，不模拟资金正确性。 */
class AdminQueryTest {
    private LinkedMultiValueMap<String,String> params(String... values) {
        var p=new LinkedMultiValueMap<String,String>();for(int i=0;i<values.length;i+=2)p.add(values[i],values[i+1]);return p;
    }
    @Test void queryRejectsUnknownDuplicateOverflowAndAmbiguousWindows() {
        for(var p:List.of(params("tenantId","1"),params("limit","1","limit","2"),params("after","-1"),params("memberId","01"),params("memberId","9223372036854775808"),params("status","NOT_A_STATE"),params("limit","101"),params("createdFrom","2026-01-01T00:00:00Z"),params("createdFrom","2026-01-01T00:00:00Z","createdTo","2026-05-01T00:00:00Z"),params("createdFrom","2026-01-01T00:00:00.000001Z","createdTo","2026-01-02T00:00:00Z"))) {
            assertEquals("INVALID_ARGUMENT",assertThrows(TradeException.class,()->AdminQueryParameters.parse("order",p)).code());
        }
        assertThrows(TradeException.class,()->AdminQueryParameters.parse("receipt",params("abnormalOnly","yes")));
        assertThrows(TradeException.class,()->AdminQueryParameters.parse("receipt",params("abnormalOnly","true","disposition","NORMAL")));
        assertThrows(TradeException.class,()->AdminQueryParameters.parse("attempt",params("createdFrom","2026-01-01T00:00:00Z","createdTo","2026-01-02T00:00:00Z")));
    }
    @Test void compatibleRefundDefaultAndExplicitAllRemainDistinct() {
        var defaultQuery=AdminQueryParameters.parse("refund",params());assertEquals("PENDING_APPROVAL",defaultQuery.filters().get("status"));
        assertEquals(0,defaultQuery.after());assertEquals(20,defaultQuery.limit());
        assertEquals("ALL",AdminQueryParameters.parse("refund",params("status","ALL")).filters().get("status"));
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"),AdminQueryParameters.parse("order",params("createdFrom","2026-01-01T00:00:00Z","createdTo","2026-01-02T00:00:00Z")).createdFrom());
    }
    @Test void pageDoesNotExposeExtraProbeRowAndEmptyHasNoNextCursor() {
        record Row(String cursor) implements AdminPage.Row {}
        var page=AdminPage.of(List.of(new Row("10"),new Row("12")),1,Instant.EPOCH);
        assertEquals(List.of(new Row("10")),page.items());assertEquals("10",page.nextAfter());assertTrue(page.hasMore());
        assertNull(AdminPage.of(List.<Row>of(),1,Instant.EPOCH).nextAfter());
    }
    @Test void evidenceDisabledDoesNotBecomeEnabledByOtherServiceConfiguration() {
        assertEquals("EVIDENCE_DISABLED",assertThrows(TradeException.class,()->new AdminEvidenceAccess(false).require()).code());
        assertDoesNotThrow(()->new AdminEvidenceAccess(true).require());
    }
    @AdminRead static class ReadEndpoint { public void query() {} }
    @Test void concurrencyLeaseIsReleasedOnFailureAndOtherTenantsShareGlobalBudget() throws Exception {
        var guard=new AdminReadConfiguration.Guard(1,1,10);var handler=new HandlerMethod(new ReadEndpoint(),ReadEndpoint.class.getMethod("query"));
        var first=new MockHttpServletRequest();var response=new MockHttpServletResponse();
        try {
            auth(1);assertTrue(guard.preHandle(first,response,handler));assertEquals("no-store",response.getHeader("Cache-Control"));
            assertEquals("ADMIN_QUERY_BUSY",assertThrows(TradeException.class,()->guard.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),handler)).code());
            auth(2);assertThrows(TradeException.class,()->guard.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),handler));
            guard.afterCompletion(first,response,handler,new IllegalStateException("failed"));
            var second=new MockHttpServletRequest();assertTrue(guard.preHandle(second,response,handler));guard.afterCompletion(second,response,handler,null);
        } finally {SecurityContextHolder.clearContext();}
    }
    @Test void tenantRateBudgetRemainsConsumedAfterRequestCompletion() throws Exception {
        var guard=new AdminReadConfiguration.Guard(2,2,1);var handler=new HandlerMethod(new ReadEndpoint(),ReadEndpoint.class.getMethod("query"));var req=new MockHttpServletRequest();var res=new MockHttpServletResponse();
        try {auth(1);guard.preHandle(req,res,handler);guard.afterCompletion(req,res,handler,null);assertThrows(TradeException.class,()->guard.preHandle(new MockHttpServletRequest(),res,handler));}
        finally {SecurityContextHolder.clearContext();}
    }
    private void auth(long tenant) {
        var jwt=Jwt.withTokenValue("test").header("alg","RS256").issuer("https://identity.test").subject("admin").claim("tenant_id",Long.toString(tenant)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
