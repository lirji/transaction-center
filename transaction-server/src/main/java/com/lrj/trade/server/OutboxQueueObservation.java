package com.lrj.trade.server;
import com.lrj.trade.core.OutboxQueueSnapshotPort;
import io.micrometer.core.instrument.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.context.SmartLifecycle;
/** 单个周期任务采样持久积压，Gauge只读不可变缓存，失败不把积压伪报为零。 */
public final class OutboxQueueObservation implements SmartLifecycle,AutoCloseable {
    private final OutboxQueueSnapshotPort source;
    private final long intervalMillis;
    private final LongAdder failures=new LongAdder();
    private volatile OutboxQueueSnapshotPort.Snapshot snapshot;
    private volatile boolean running;
    private ScheduledThreadPoolExecutor executor;
    /** 三种固定状态标签，无租户、事件或工单高基数标签。 */
    public OutboxQueueObservation(OutboxQueueSnapshotPort source,MeterRegistry meters,long intervalMillis) {
        this.source=java.util.Objects.requireNonNull(source);
        if(intervalMillis<5000 || intervalMillis>60000) throw new IllegalArgumentException("队列采样间隔必须为5至60秒");
        this.intervalMillis=intervalMillis;
        for(String status:new String[]{"READY","RUNNING","REVIEW"}) {
            Gauge.builder("trade.outbox.queue.count.lower.bound",this,o->o.count(status)).tag("status",status).register(meters);
            Gauge.builder("trade.outbox.queue.oldest.age.seconds",this,o->o.age(status)).tag("status",status).register(meters);
        }
        Gauge.builder("trade.outbox.queue.observed.at.seconds",this,o->o.snapshot==null?0:o.snapshot.observedAt().toEpochMilli()/1000.0).register(meters);
        FunctionCounter.builder("trade.outbox.queue.observation.failures",failures,LongAdder::sum).register(meters);
    }
    /** 同步采样用于受控验证；业务HTTP不会调用，故障只累计分类不输出原异常。 */
    public void refresh() {
        try { snapshot=source.snapshot(); }
        catch(RuntimeException error) { failures.increment(); }
    }
    private double count(String status) {
        var current=snapshot;if(current==null) return Double.NaN;
        return current.states().stream().filter(s->s.status().equals(status)).findFirst().map(s->(double)s.countLowerBound()).orElse(Double.NaN);
    }
    private double age(String status) {
        var current=snapshot;if(current==null) return Double.NaN;
        return current.states().stream().filter(s->s.status().equals(status)).findFirst()
            .map(s->s.oldestAt()==null?0.0:(double)Math.max(0,Duration.between(s.oldestAt(),Instant.now()).toSeconds())).orElse(Double.NaN);
    }
    /** 队列中只有一个周期任务，没有外部提交入口或无界请求积压。 */
    @Override public synchronized void start() {
        if(running) return;
        if(executor!=null && !executor.isTerminated()) throw new IllegalStateException("旧采样线程尚未退出");
        executor=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"trade-outbox-observation");t.setDaemon(true);return t;});
        executor.setRemoveOnCancelPolicy(true);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        running=true;executor.scheduleWithFixedDelay(this::refresh,0,intervalMillis,TimeUnit.MILLISECONDS);
    }
    /** 三秒查询预算内排空，超五秒请求中断，旧线程未退出时不能增开。 */
    @Override public synchronized void stop() {
        running=false;if(executor==null) return;executor.shutdown();
        try { if(!executor.awaitTermination(5,TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch(InterruptedException interrupted) { executor.shutdownNow();Thread.currentThread().interrupt(); }
    }
    /** 生命周期回调在有界停止之后继续。 */
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    /** 表示是否继续调度，不替代观测样本新鲜度。 */
    @Override public boolean isRunning() { return running; }
    /** 仅worker配置创建本实例，普通API不启动采样线程。 */
    @Override public boolean isAutoStartup() { return true; }
    /** 先于数据源销毁停止查询。 */
    @Override public int getPhase() { return Integer.MAX_VALUE-1024; }
    /** 测试与容器使用相同排空路径。 */
    @Override public void close() { stop(); }
}
