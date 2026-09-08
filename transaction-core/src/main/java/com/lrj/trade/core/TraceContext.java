package com.lrj.trade.core;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.MDC;
/** 有界W3C追踪上下文仅用于观测，不承载身份或资金授权。 */
public record TraceContext(String traceId,String spanId,boolean sampled) {
    private static final ThreadLocal<TraceContext> CURRENT=new ThreadLocal<>();
    private static final SecureRandom RANDOM=new SecureRandom();
    /** 只允许规范非零标识，避免产生下游必须拒绝的traceparent。 */
    public TraceContext {
        if(traceId==null || !traceId.matches("[0-9a-f]{32}") || traceId.equals("0".repeat(32))
            || spanId==null || !spanId.matches("[0-9a-f]{16}") || spanId.equals("0".repeat(16))) throw new IllegalArgumentException("追踪标识无效");
    }
    /** 当前线程未进入HTTP或任务作用域时返回null，不隐式污染线程池。 */
    public static TraceContext current() { return CURRENT.get(); }
    /** 数据库只捕获已建立上下文；历史或非入口调用允许NULL。 */
    public static String currentParent() { var current=CURRENT.get();return current==null?null:current.parent(); }
    /** 出站及持久化统一00版55字符，仅保留sampled位。 */
    public String parent() { return "00-"+traceId+"-"+spanId+(sampled?"-01":"-00"); }
    /** 既有事件仍使用规范UUID文本，128位值与W3C trace-id一致。 */
    public String uuid() { return traceId.substring(0,8)+"-"+traceId.substring(8,12)+"-"+traceId.substring(12,16)+"-"+traceId.substring(16,20)+"-"+traceId.substring(20); }
    /** 无入口上下文的旧内部调用继续产生独立关联，不冒充已有上游链路。 */
    public static String correlationUuid() { var current=CURRENT.get();return current==null?UUID.randomUUID().toString():current.uuid(); }
    /** 不采样的新根使用随机标识，避免输入坏头阻断业务。 */
    public static TraceContext root() { return new TraceContext(randomHex(16),randomHex(8),false); }
    /** 每次服务或任务处理创建新span，保持同一追踪根和采样位。 */
    public TraceContext child() { return new TraceContext(traceId,randomHex(8),sampled); }
    /** 坏头忽略；较新版本仅解释已知前缀，扩展内容从不存储或输出。 */
    public static TraceContext parse(String value) {
        if(value==null || value.length()<55 || value.length()>512 || value.indexOf(',')>=0) return null;
        String prefix=value.substring(0,55);
        if(!prefix.matches("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}") || prefix.startsWith("ff-"))return null;
        if(prefix.startsWith("00-") && value.length()!=55)return null;
        if(value.length()>55 && value.charAt(55)!='-')return null;
        for(int i=55;i<value.length();i++) if(value.charAt(i)<33 || value.charAt(i)>126)return null;
        String trace=prefix.substring(3,35),span=prefix.substring(36,52);
        if(trace.equals("0".repeat(32)) || span.equals("0".repeat(16)))return null;
        return new TraceContext(trace,span,(Integer.parseInt(prefix.substring(53,55),16)&1)!=0);
    }
    /** 入口和持久任务均创建子span，显式close恢复嵌套线程及MDC状态。 */
    public static Scope open(String parent) { var parsed=parse(parent);return new Scope(parsed==null?root():parsed.child()); }
    /** Kafka按持久事件根发送；补漏或人工重放不得用当前请求根覆盖原事件。 */
    public static String parentForEvent(String uuid) {
        String trace=UUID.fromString(uuid).toString().replace("-","");
        if(trace.equals("0".repeat(32)))return root().parent();
        var current=CURRENT.get();
        return current!=null && current.traceId.equals(trace)?current.parent():new TraceContext(trace,randomHex(8),false).parent();
    }
    private static String randomHex(int bytes) {
        byte[] value=new byte[bytes];String hex;
        do {RANDOM.nextBytes(value);hex=HexFormat.of().formatHex(value);} while(hex.equals("0".repeat(bytes*2)));
        return hex;
    }
    /** 非继承线程作用域，要求原线程按嵌套顺序关闭，防止误清除另一请求。 */
    public static final class Scope implements AutoCloseable {
        private final Thread owner=Thread.currentThread();
        private final TraceContext previous=CURRENT.get(),installed;
        private final String previousTrace=MDC.get("traceId"),previousSpan=MDC.get("spanId");
        private boolean closed;
        private Scope(TraceContext context) {installed=context;CURRENT.set(context);MDC.put("traceId",context.traceId);MDC.put("spanId",context.spanId);}
        /** 异常返回也恢复旧上下文；重复close不改变调用方状态。 */
        @Override public void close() {
            if(closed)return;
            if(Thread.currentThread()!=owner || CURRENT.get()!=installed)throw new IllegalStateException("追踪作用域必须原线程按顺序关闭");
            closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);
            restore("traceId",previousTrace);restore("spanId",previousSpan);
        }
        private void restore(String key,String value) {if(value==null)MDC.remove(key);else MDC.put(key,value);}
    }
}
