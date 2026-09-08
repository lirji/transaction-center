package com.lrj.trade.jobs;
import com.lrj.trade.core.OutboxMaintenancePort;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import org.springframework.context.SmartLifecycle;
/** 独立单线程维护通道，固定页速率，不占用发布ACK或资金恢复线程。 */
public final class OutboxMaintenanceRuntime implements SmartLifecycle,AutoCloseable {
    private final OutboxMaintenancePort maintenance;
    private final int pageSize;
    private final long intervalMillis,tickMillis;
    private final LongAdder polls=new LongAdder(),pages=new LongAdder(),scanned=new LongAdder(),candidates=new LongAdder(),rounds=new LongAdder(),errors=new LongAdder();
    private volatile boolean running;
    private volatile long lastPollMillis;
    private Thread thread;
    /** 每tick只处理一个租户的一页，防止维护流量挤占业务数据库连接。 */
    public OutboxMaintenanceRuntime(OutboxMaintenancePort maintenance,int pageSize,long intervalMillis,long tickMillis) {
        this.maintenance=java.util.Objects.requireNonNull(maintenance);
        if(pageSize<1 || pageSize>100 || intervalMillis<60000 || intervalMillis>86400000 || tickMillis<50 || tickMillis>60000)
            throw new IllegalArgumentException("维护容量或时间配置无效");
        this.pageSize=pageSize;this.intervalMillis=intervalMillis;this.tickMillis=tickMillis;
    }
    /** 旧线程尚未退出时拒绝再启，防止不响应中断的依赖突破容量。 */
    @Override public synchronized void start() {
        if(running) return;
        if(thread!=null && thread.isAlive()) throw new IllegalStateException("旧维护线程尚未退出");
        running=true;thread=new Thread(this::loop,"trade-outbox-maintenance");thread.setDaemon(true);thread.start();
    }
    private void loop() {
        long tenantCursor=0;int failures=0;
        while(running && !Thread.currentThread().isInterrupted()) {
            long pause=tickMillis;
            try {
                var tenant=maintenance.nextTenant(tenantCursor);
                if(!running) break;
                if(tenant.isEmpty()) { tenantCursor=0;pause=Math.max(1000,tickMillis); }
                else {
                    tenantCursor=tenant.getAsLong();
                    var step=maintenance.scanPage(tenantCursor,pageSize,intervalMillis);
                    if(step.worked()) { pages.increment();scanned.add(step.scanned());candidates.add(step.candidates());if(step.completed()) rounds.increment(); }
                }
                polls.increment();lastPollMillis=System.currentTimeMillis();failures=0;
            } catch(RuntimeException error) {
                errors.increment();failures=Math.min(6,failures+1);pause=Math.min(30000,1000L << (failures-1));
                org.slf4j.LoggerFactory.getLogger(OutboxMaintenanceRuntime.class).warn("历史发布维护失败，将按持久游标重试");
            }
            if(running) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(pause));
        }
    }
    /** 停止发现，最多等待10秒使已进入的5秒数据库页事务收尾。 */
    @Override public synchronized void stop() {
        running=false;
        if(thread==null) return;
        LockSupport.unpark(thread);
        try { thread.join(10000); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if(thread.isAlive()) thread.interrupt();
    }
    /** Spring在有界排空后才继续销毁数据源。 */
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    /** 只表示是否继续接受扫描，不保证异常依赖已经响应中断。 */
    @Override public boolean isRunning() { return running; }
    /** 只有显式维护配置创建此对象，默认应用不创建。 */
    @Override public boolean isAutoStartup() { return true; }
    /** 与任务运行时同阶段停止，早于基础数据源销毁。 */
    @Override public int getPhase() { return Integer.MAX_VALUE-1024; }
    /** 手工运行和容器销毁共用有界停机。 */
    @Override public void close() { stop(); }
    /** 固定低基数运行统计，不冒充持久积压量。 */
    public Statistics statistics() { return new Statistics(polls.sum(),pages.sum(),scanned.sum(),candidates.sum(),rounds.sum(),errors.sum(),lastPollMillis); }
    /** 候选和扫描是本进程累计观察量，不等于数据库新增任务量。 */
    public record Statistics(long polls,long pages,long scanned,long candidates,long rounds,long errors,long lastPollMillis) {}
}
