package com.lrj.trade.server;

import com.lrj.trade.core.AutomaticAdmissionGate;
import com.lrj.trade.core.OutboxQueueSnapshotPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.context.SmartLifecycle;

/** 合并持久积压与数据库磁盘信号，滞回避免阈值附近反复开关新增交易。 */
public final class AutomaticAdmissionSampler implements SmartLifecycle,AutoCloseable {
    private final AutomaticAdmissionGate gate;
    private final OutboxQueueSnapshotPort queue;
    private final LongSupplier disk;
    private final Clock clock;
    private final long high,low,minBytes,recoveryBytes,interval;
    private boolean paused=true;
    private final Object stateLock=new Object();
    private final java.util.concurrent.locks.ReentrantLock refreshLock=new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean closed;
    private long generation;
    private volatile boolean running;
    private ScheduledThreadPoolExecutor executor;
    /** 阈值限制在计数下界能判定的范围，必须为恢复资金业务保留磁盘预算。 */
    public AutomaticAdmissionSampler(AutomaticAdmissionGate gate,OutboxQueueSnapshotPort queue,LongSupplier disk,
            Clock clock,MeterRegistry meters,long high,long low,long minBytes,long recoveryBytes,long interval) {
        if(high<1 || high>10001 || low<0 || low>=high || minBytes<1 || recoveryBytes<=minBytes || interval<5000 || interval>60000)
            throw new IllegalArgumentException("自动准入阈值或采样周期无效");
        this.gate=gate;this.queue=queue;this.disk=disk;this.clock=clock;this.high=high;this.low=low;
        this.minBytes=minBytes;this.recoveryBytes=recoveryBytes;this.interval=interval;
        Gauge.builder("trade.automatic.admission.blocked",gate,g->g.blocked()?1:0).register(meters);
    }
    /** 一次采样有任意不完整信号立即关闭，不沿用旧健康样本掩盖故障。 */
    public void refresh() {
        if(closed || !refreshLock.tryLock()) return;
        final long started;
        synchronized(stateLock) {started=generation;}
        try {
            var snapshot=queue.snapshot();long free=disk.getAsLong();long count=0;
            var statuses=new HashSet<String>();
            for(var state:snapshot.states()) {
                if(!statuses.add(state.status()) || state.countLowerBound()<0 || state.countLowerBound()>10001)
                    throw new IllegalStateException("积压样本无效");
                count+=state.countLowerBound();
            }
            if(!statuses.equals(Set.of("READY","RUNNING","REVIEW")) || free<0 || !gate.fresh(snapshot.observedAt())) throw new IllegalStateException("安全信号缺失或时间无效");
            synchronized(stateLock) {
                if(closed || generation!=started) return;
                // 过期曾关闭许可时也重新经过低水位，不能因内存旧状态绕过滞回。
                paused=(paused || gate.blocked()) ? !(count<=low && free>=recoveryBytes) : count>=high || free<minBytes;
                gate.publish(paused,snapshot.observedAt());
            }
        } catch(RuntimeException error) { synchronized(stateLock) {if(generation==started) {paused=true;gate.unavailable();}} }
        finally {refreshLock.unlock();}
    }
    /** 单个固定延迟任务，不积累外部提交；旧探测线程未退出时不能增开线程。 */
    @Override public synchronized void start() {
        if(running) return;
        if(executor!=null && !executor.isTerminated()) throw new IllegalStateException("旧自动准入采样尚未退出");
        synchronized(stateLock) {generation++;closed=false;gate.unavailable();paused=true;}
        executor=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"trade-automatic-admission");t.setDaemon(true);return t;});
        executor.setRemoveOnCancelPolicy(true);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        running=true;executor.scheduleWithFixedDelay(this::refresh,0,interval,TimeUnit.MILLISECONDS);
    }
    /** 先关闭新增许可再有界排空；底层文件系统阻塞无法强制终止时保持失败关闭。 */
    @Override public synchronized void stop() {
        running=false;
        synchronized(stateLock) {generation++;closed=true;gate.unavailable();}
        var current=executor;if(current==null) return;current.shutdown();
        try { if(!current.awaitTermination(5,TimeUnit.SECONDS)) current.shutdownNow(); }
        catch(InterruptedException error) { current.shutdownNow();Thread.currentThread().interrupt(); }
        gate.unavailable();
    }
    /** 生命周期停止后再通知容器，回调不能跳过关闭许可。 */
    @Override public void stop(Runnable callback) { try {stop();} finally {callback.run();} }
    /** 是否继续调度与快照是否新鲜分别判断。 */
    @Override public boolean isRunning() { return running; }
    /** 只有显式启用的配置才创建本采样器。 */
    @Override public boolean isAutoStartup() { return true; }
    /** 在数据源关闭前停止调度。 */
    @Override public int getPhase() { return Integer.MAX_VALUE-1024; }
    /** 测试与容器共用关闭路径。 */
    @Override public void close() { stop(); }
}
