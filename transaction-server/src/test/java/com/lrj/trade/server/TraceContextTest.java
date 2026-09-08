package com.lrj.trade.server;
import com.lrj.trade.core.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
/** 追踪格式、作用域和审计关联不改变授权或业务结果。 */
class TraceContextTest {
    static final String PARENT="00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    @Test void validAndFutureVersionsNormalizeWithoutLosingTraceOrSampledBit() {
        var current=TraceContext.parse(PARENT);assertNotNull(current);assertEquals(PARENT,current.parent());
        assertEquals(current.traceId(),TraceContext.parse("01"+PARENT.substring(2)+"-future").traceId());
        assertEquals(PARENT,TraceContext.parse("fe"+PARENT.substring(2,53)+"ff-tail").parent());
        assertEquals(current.traceId(),current.uuid().replace("-",""));
        for(String bad:List.of("",PARENT+"-tail","ff"+PARENT.substring(2),PARENT.toUpperCase(),PARENT.replace("4bf92f3577b34da6a3ce929d0e0e4736","0".repeat(32)),PARENT.replace("00f067aa0ba902b7","0".repeat(16)),PARENT+","+PARENT,"01"+PARENT.substring(2)+"-ext,"+PARENT,"01"+PARENT.substring(2)+"-"+"x".repeat(500)))assertNull(TraceContext.parse(bad),bad);
        assertNull(TraceContext.parse(null));assertFalse(TraceContext.root().sampled());
    }
    @Test void nestedScopeRestoresMdcAndRejectsOutOfOrderClosure() {
        assertNull(TraceContext.current());MDC.put("traceId","prior");
        try {
            var outer=TraceContext.open(PARENT);var saved=TraceContext.current();
            var inner=TraceContext.open(null);assertNotEquals(saved.traceId(),TraceContext.current().traceId());
            assertThrows(IllegalStateException.class,outer::close);inner.close();assertSame(saved,TraceContext.current());
            assertEquals(saved.traceId(),MDC.get("traceId"));outer.close();outer.close();assertNull(TraceContext.current());assertEquals("prior",MDC.get("traceId"));
        } finally {MDC.remove("traceId");}
    }
    @Test void pooledThreadDoesNotInheritOrLeakEvenWhenTaskThrows() throws Exception {
        var pool=Executors.newSingleThreadExecutor();
        try(var parent=TraceContext.open(PARENT)) {
            assertNull(pool.submit(TraceContext::current).get());
            assertThrows(ExecutionException.class,()->pool.submit(()->{try(var scope=TraceContext.open(PARENT)){throw new IllegalStateException("test");}}).get());
            assertNull(pool.submit(TraceContext::current).get());assertNull(pool.submit(()->MDC.get("traceId")).get());
        } finally {pool.shutdownNow();}
    }
    @Test void filterHandlesMultipleValuesAndAlwaysCleansUp() throws Exception {
        var filter=new TraceContextFilter();var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();request.addHeader("traceparent",PARENT);
        filter.doFilter(request,response,(req,res)->assertEquals(TraceContext.parse(PARENT).traceId(),TraceContext.current().traceId()));
        assertNotEquals(PARENT,response.getHeader("traceparent"));assertEquals(TraceContext.parse(PARENT).traceId(),TraceContext.parse(response.getHeader("traceparent")).traceId());assertNull(TraceContext.current());
        request=new MockHttpServletRequest();response=new MockHttpServletResponse();request.addHeader("traceparent",PARENT);request.addHeader("traceparent",PARENT);
        filter.doFilter(request,response,(req,res)->assertNotEquals(TraceContext.parse(PARENT).traceId(),TraceContext.current().traceId()));assertNull(TraceContext.current());
        var failingRequest=new MockHttpServletRequest();var failingResponse=new MockHttpServletResponse();failingRequest.addHeader("traceparent",PARENT);
        assertThrows(jakarta.servlet.ServletException.class,()->filter.doFilter(failingRequest,failingResponse,(req,res)->{throw new jakarta.servlet.ServletException("test");}));assertNull(TraceContext.current());
    }
    @Test void normalErrorsUseRequestTraceButApprovalKeepsPermanentAuditReference() {
        try(var scope=TraceContext.open(PARENT)) {
            var errors=new Errors();assertEquals(TraceContext.current().uuid(),errors.business(new TradeException("NOT_FOUND","missing")).getBody().traceId());
            assertEquals("ARJ-permanent",errors.approvalRejected(new ApprovalRejection("REFUND_APPROVAL_CONFLICT","conflict","ARJ-permanent")).getBody().traceId());
        }
    }
}
