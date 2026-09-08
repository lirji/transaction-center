package com.lrj.trade.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 自动保护只收紧人工准入，读取内存快照，不能把磁盘或网络探测放进交易事务。 */
@Component
public final class AutomaticAdmissionGate {
    private final boolean enabled;
    private final Clock clock;
    private final Duration maxAge;
    private final LongSupplier ticker;
    private final AtomicReference<Sample> sample=new AtomicReference<>();
    /** 明确启用后首个健康样本之前失败关闭，默认关闭兼容已有部署。 */
    @Autowired
    public AutomaticAdmissionGate(@Value("${trade.automatic-admission.enabled:false}") boolean enabled, Clock clock,
            @Value("${trade.automatic-admission.max-age-millis:45000}") long maxAgeMillis) {
        this(enabled,clock,maxAgeMillis,System::nanoTime);
    }
    /** 单调时钟独立于业务墙钟，测试可复现回拨而无需修改系统时间。 */
    public AutomaticAdmissionGate(boolean enabled,Clock clock,long maxAgeMillis,LongSupplier ticker) {
        if(maxAgeMillis<5000 || maxAgeMillis>300000) throw new IllegalArgumentException("自动准入样本有效期须为5至300秒");
        this.enabled=enabled;this.clock=clock;this.maxAge=Duration.ofMillis(maxAgeMillis);this.ticker=ticker;
    }
    /** 先按原样本年龄扣除剩余寿命，再用单调计时确保墙钟回拨不延长许可。 */
    public void publish(boolean paused, Instant observedAt) {
        long started=ticker.getAsLong();Instant now=clock.instant();
        if(!freshAt(observedAt,now)) {sample.set(null);return;}
        long remaining=maxAge.minus(Duration.between(observedAt,now)).toNanos();
        sample.set(new Sample(paused,observedAt,started,remaining));
    }
    /** 采样器推进滞回之前必须拒绝过期或未来数据，不能用旧低水位恢复。 */
    public boolean fresh(Instant observedAt) {return freshAt(observedAt,clock.instant());}
    private boolean freshAt(Instant observedAt,Instant now) {
        return observedAt!=null && !observedAt.isAfter(now) && Duration.between(observedAt,now).compareTo(maxAge)<0;
    }
    /** 停止采样或探测失败时立即关闭；后续必须有新的完整健康样本才能恢复。 */
    public void unavailable() { sample.set(null); }
    /** 失效样本原子废弃；即使墙钟跳变后恢复，也不能重新使用曾失效的许可。 */
    public boolean blocked() {
        if(!enabled) return false;
        Sample current=sample.get();
        if(current==null || current.paused()) return true;
        long elapsed=ticker.getAsLong()-current.startedNanos();
        if(elapsed<0 || elapsed>=current.remainingNanos() || !fresh(current.observedAt())) {
            sample.compareAndSet(current,null);return true;
        }
        return false;
    }
    /** 保留人工配置版本，自动恢复绝不打开管理员手动关闭的业务。 */
    public AdmissionPolicy apply(AdmissionPolicy policy) {
        return blocked()?new AdmissionPolicy(false,false,policy.version()):policy;
    }
    /** 供采样器检查部署是否明确启用，不提供远程开关入口。 */
    public boolean enabled() { return enabled; }
    /** 不可变状态避免交易线程看到跨字段撕裂。 */
    private record Sample(boolean paused, Instant observedAt,long startedNanos,long remainingNanos) {}
}
