package com.lrj.trade.server;
import com.lrj.trade.core.FinancialSnapshotPort;
import io.micrometer.core.instrument.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.context.SmartLifecycle;
/** 单线程按域采样，HTTP抓取仅访问不可变快照，查询失败不把资金积压清零。 */
public final class FinancialObservation implements SmartLifecycle,AutoCloseable {
    private final List<Domain> domains;
    private final long interval;
    private final Clock clock;
    private volatile boolean running;
    private ScheduledThreadPoolExecutor executor;
    /** 单域样本与失败计数独立保存，其他域成功不能覆盖其故障。 */
    private static final class Domain {
        final FinancialSnapshotPort source;
        final List<String> keys;
        final LongAdder failures=new LongAdder();
        volatile FinancialSnapshotPort.Snapshot snapshot;
        Domain(FinancialSnapshotPort source) { this.source=source;this.keys=List.copyOf(source.keys()); }
        double count(String key) { var s=snapshot;return s==null?Double.NaN:row(s,key).countLowerBound(); }
        double age(String key,Instant now) { var s=snapshot;if(s==null)return Double.NaN;var at=row(s,key).oldestAt();return at==null?0:Math.max(0,Duration.between(at,now).toSeconds()); }
        double observed() { var s=snapshot;return s==null?0:s.rows().getFirst().observedAt().toEpochMilli()/1000.0; }
        static FinancialSnapshotPort.Row row(FinancialSnapshotPort.Snapshot s,String key) { return s.rows().stream().filter(r->r.metric().equals(key)).findFirst().orElseThrow(); }
        void refresh() {
            try {
                var result=source.snapshot();
                if(!result.rows().stream().map(FinancialSnapshotPort.Row::metric).toList().equals(keys)) throw new IllegalStateException("观测结果不完整");
                Instant observed=null;
                for(var row:result.rows()) {
                    if(row.countLowerBound()<0 || row.countLowerBound()>10001 || row.observedAt()==null
                        || (row.countLowerBound()==0)!=(row.oldestAt()==null)) throw new IllegalStateException("观测样本无效");
                    if(observed!=null && !observed.equals(row.observedAt())) throw new IllegalStateException("单域样本时间不一致");
                    observed=row.observedAt();
                }
                snapshot=result;
            } catch(RuntimeException failure) { failures.increment(); }
        }
    }
    /** 来源域固定、无业务标签；时钟注入使年龄计算可复现。 */
    public FinancialObservation(List<FinancialSnapshotPort> sources,MeterRegistry meters,Clock clock,long interval) {
        if(interval<5000 || interval>60000) throw new IllegalArgumentException("观测间隔必须为5至60秒");
        if(sources.isEmpty() || sources.size()>3 || sources.stream().map(FinancialSnapshotPort::domain).distinct().count()!=sources.size()) throw new IllegalArgumentException("观测域重复或缺失");
        this.clock=Objects.requireNonNull(clock);this.interval=interval;this.domains=sources.stream().map(Domain::new).toList();
        for(var domain:domains) {
            if(!Set.of("payment","refund","reconciliation").contains(domain.source.domain()) || domain.keys.isEmpty()) throw new IllegalArgumentException("未知观测域");
            for(String key:domain.keys) {
                Gauge.builder("trade.financial.backlog.count.lower.bound",domain,d->d.count(key)).tag("domain",domain.source.domain()).tag("state",key).register(meters);
                Gauge.builder("trade.financial.backlog.oldest.age.seconds",domain,d->d.age(key,clock.instant())).tag("domain",domain.source.domain()).tag("state",key).register(meters);
            }
            Gauge.builder("trade.financial.observed.at.seconds",domain,Domain::observed).tag("domain",domain.source.domain()).register(meters);
            FunctionCounter.builder("trade.financial.observation.failures",domain.failures,LongAdder::sum).tag("domain",domain.source.domain()).register(meters);
        }
    }
    /** 各域失败隔离；仅后台周期任务与受控测试调用。 */
    public void refresh() { for(var domain:domains) domain.refresh(); }
    /** 单个固定延迟任务，不接受请求入队，不会因慢查询积累周期任务。 */
    @Override public synchronized void start() {
        if(running)return;
        if(executor!=null && !executor.isTerminated()) throw new IllegalStateException("旧资金观测线程尚未退出");
        executor=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"trade-financial-observation");t.setDaemon(true);return t;});
        executor.setRemoveOnCancelPolicy(true);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        running=true;executor.scheduleWithFixedDelay(this::refresh,0,interval,TimeUnit.MILLISECONDS);
    }
    /** 最多三域各三秒查询预算，停止调度后有界排空。 */
    @Override public synchronized void stop() {
        running=false;if(executor==null)return;executor.shutdown();
        try { if(!executor.awaitTermination(10,TimeUnit.SECONDS))executor.shutdownNow(); }
        catch(InterruptedException interrupted) { executor.shutdownNow();Thread.currentThread().interrupt(); }
    }
    /** 完成有界排空后通知容器。 */
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    /** 生命周期状态不能替代样本新鲜度。 */
    @Override public boolean isRunning() { return running; }
    /** 仅worker配置创建实例。 */
    @Override public boolean isAutoStartup() { return true; }
    /** 先于数据源销毁，避免关闭期间发起新查询。 */
    @Override public int getPhase() { return Integer.MAX_VALUE-1024; }
    /** 测试及容器共享有界停止路径。 */
    @Override public void close() { stop(); }
}
