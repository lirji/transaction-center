package com.lrj.trade.server;
import com.lrj.trade.core.TraceContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/** 身份过滤前建立请求观测上下文，坏追踪头不能绕过或阻断业务权限。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class TraceContextFilter extends OncePerRequestFilter {
    /** 不记录原始头或baggage，响应头关联请求；finally清理复用线程。 */
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var values=request.getHeaders("traceparent");String parent=null;
        if(values!=null && values.hasMoreElements()) {parent=values.nextElement();if(values.hasMoreElements())parent=null;}
        try(var scope=TraceContext.open(parent)) {
            response.setHeader("traceparent",TraceContext.currentParent());
            chain.doFilter(request,response);
        }
    }
}
