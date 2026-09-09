package com.lrj.trade.server;

import com.lrj.trade.core.*;
import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** 管理查询单独限制并发和租户速率，避免运营扫描耗尽交易连接；多实例仍需网关总配额。 */
@Configuration
public class AdminReadConfiguration implements WebMvcConfigurer {
    private final Guard guard;
    /** 配额是每个API实例的保护阈值，不冒充集群限流。 */
    public AdminReadConfiguration(@Value("${trade.admin-query.concurrency:4}") int concurrency,
            @Value("${trade.admin-query.tenant-concurrency:2}") int tenantConcurrency,
            @Value("${trade.admin-query.tenant-rate:20}") int rate) {
        if(concurrency<1 || tenantConcurrency<1 || rate<1) throw new IllegalArgumentException("管理查询配额必须为正数");
        guard=new Guard(concurrency,tenantConcurrency,rate);
    }
    /** 仅标注过的查询入口受此约束，不改变已有命令的处理流程。 */
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(guard); }
    /** 单租户窗口计数，所有访问由Guard监视器串行化。 */
    static final class Bucket { int active,count;long window;Bucket(long now) {window=now;} }
    /** 请求生命周期内持有并发槽位，正常和异常完成均释放。 */
    static final class Guard implements HandlerInterceptor {
        private static final String LEASE=Guard.class.getName();
        private final Map<Long,Bucket> buckets=new HashMap<>();private final int globalLimit,tenantLimit,rate;private int active;
        Guard(int globalLimit,int tenantLimit,int rate) { this.globalLimit=globalLimit;this.tenantLimit=tenantLimit;this.rate=rate; }
        /** 立即拒绝超配额，不在请求线程中排队占用连接。 */
        @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
            if(!(handler instanceof HandlerMethod method) || !(method.getBeanType().isAnnotationPresent(AdminRead.class) || method.hasMethodAnnotation(AdminRead.class))) return true;
            response.setHeader("Cache-Control","no-store");
            var principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if(!(principal instanceof Jwt jwt)) throw new TradeException("INVALID_IDENTITY","缺少可信身份");
            long tenant=Long.parseLong(jwt.getClaimAsString("tenant_id"));long now=System.nanoTime();
            try { synchronized(this) {
                buckets.entrySet().removeIf(e->e.getValue().active==0 && now-e.getValue().window>60_000_000_000L);
                Bucket b=buckets.get(tenant);
                if(b==null) { if(buckets.size()>=1024) throw busy();b=new Bucket(now);buckets.put(tenant,b); }
                if(now-b.window>=1_000_000_000L) {b.window=now;b.count=0;}
                if(active>=globalLimit || b.active>=tenantLimit || b.count>=rate) throw busy();
                active++;b.active++;b.count++;request.setAttribute(LEASE,tenant);
            } } catch(TradeException rejected) {
                audit(request,jwt,tenant,429);throw rejected;
            }
            return true;
        }
        /** 成功、参数错误和服务异常均释放配额；审计只记摘要，不记录请求体或证据全文。 */
        @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception exception) {
            Object held=request.getAttribute(LEASE);if(!(held instanceof Long tenant)) return;
            synchronized(this) {active--;buckets.get(tenant).active--;request.removeAttribute(LEASE);}
            var principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if(principal instanceof Jwt jwt) audit(request,jwt,tenant,response.getStatus());
        }
        private void audit(HttpServletRequest request,Jwt jwt,long tenant,int status) {
            org.slf4j.LoggerFactory.getLogger("trade.admin.query.audit").info(
                "tenant={} actor={} resourceHash={} httpStatus={} traceId={}",tenant,Checks.hash(jwt.getIssuer().toString(),jwt.getSubject()),
                Checks.hash(request.getMethod(),request.getRequestURI()),status,TraceContext.correlationUuid());
        }
        private TradeException busy() {return new TradeException("ADMIN_QUERY_BUSY","管理查询繁忙，请稍后重试");}
    }
}
