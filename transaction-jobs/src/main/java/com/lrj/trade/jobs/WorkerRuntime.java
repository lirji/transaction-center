package com.lrj.trade.jobs;

import com.lrj.trade.core.JobType;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** 固定通道常驻执行器；内存只保存轮转游标，任务事实、定时、租约全部以数据库为准。 */
public final class WorkerRuntime implements SmartLifecycle,AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(WorkerRuntime.class);
    private final JobTenantDiscovery discovery;
    private final EnumMap<JobType,Batch> batches;
    private final Settings settings;
    private final EnumMap<JobType,Lane> lanes=new EnumMap<>(JobType.class);
    private final String instance=UUID.randomUUID().toString();
    private volatile boolean running;

    /** 处理器固定注册且覆盖白名单全部类型，不能运行用户提交的类名或命令。 */
    public WorkerRuntime(JobTenantDiscovery discovery,Map<JobType,Batch> batches,Settings settings) {
        if(discovery==null || settings==null || batches==null || !batches.keySet().equals(EnumSet.allOf(JobType.class)))
            throw new IllegalArgumentException("必须提供全部任务类型的处理器");
        this.discovery=discovery; this.batches=new EnumMap<>(JobType.class); this.batches.putAll(batches);
        this.batches.values().forEach(Objects::requireNonNull); this.settings=settings;
        for(var type:JobType.values()) lanes.put(type,new Lane(type));
    }

    /** 重复启动幂等；上次停机仍有不响应中断的调用时拒绝再建线程突破容量上限。 */
    @Override public synchronized void start() {
        if(running) return;
        if(lanes.values().stream().anyMatch(l->l.pool!=null && !l.pool.isTerminated()))
            throw new IllegalStateException("旧执行线程尚未退出");
        running=true;
        try {
            for(var lane:lanes.values()) {
                int threads=settings.threads(lane.type);
                lane.workers.clear();
                if(threads==0) continue; // 默认关闭的发布通道不进行租户发现或领取。
                lane.pool=new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(threads),r->{
                    Thread thread=new Thread(r,"trade-worker-"+lane.type+"-"+instance);
                    thread.setDaemon(true); lane.workers.add(thread); return thread;
                },new ThreadPoolExecutor.AbortPolicy());
                for(int i=0;i<threads;i++) {
                    String owner=instance+":"+lane.type+":"+i;
                    lane.pool.execute(()->loop(lane,owner));
                }
            }
        } catch(RuntimeException startFailure) {
            running=false;
            lanes.values().forEach(l->{ if(l.pool!=null) l.pool.shutdownNow(); });
            throw startFailure;
        }
    }

    private void loop(Lane lane,String owner) {
        int failures=0,emptyProbes=0;
        while(running && !Thread.currentThread().isInterrupted()) {
            long began=System.nanoTime(); int handled=0; boolean failed=false,idleRound=false;
            lane.active.incrementAndGet();
            try {
                var visit=lane.next(); var tenant=visit.tenant(); idleRound=visit.idleRound();
                // 停机期间发现返回后不再调用处理器；已经进入的单任务批次属于在途收尾。
                if(!running) break;
                if(tenant.isPresent()) {
                    handled=batches.get(lane.type).run(tenant.getAsLong(),owner);
                    if(handled<0 || handled>1) throw new IllegalStateException("运行时每批只允许一个任务");
                    lane.polls.increment(); lane.handled.add(handled);
                }
                failures=0;
            } catch(RuntimeException error) {
                failed=true; failures=Math.min(6,failures+1); lane.errors.increment();
                // 不输出异常message/cause，避免渠道报文或凭据进入后台日志。
                LOG.warn("交易后台轮询失败，类型={}，将退避重试",lane.type);
            } finally {
                lane.elapsedNanos.add(Math.max(0,System.nanoTime()-began));
                lane.active.decrementAndGet();
            }
            if(!running) break;
            // 不对每个未来任务租户都睡眠，否则一千个空租户会人为增加一千倍空闲延迟。
            // 固定段探测后让出线程，前一整轮无完成工作且本次也无工作才空闲。
            emptyProbes=handled>0?0:emptyProbes+1;
            long pause=failed?Math.min(30000L,1000L << (failures-1)):
                (handled>0?0:(idleRound?settings.idleMillis():(emptyProbes%64==0?1:0)));
            if(pause>0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(pause));
        }
    }

    /** 停止新轮询并唤醒空闲线程；等待已进入的有界单任务批次，超过总时限才请求中断。 */
    @Override public synchronized void stop() {
        running=false;
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(settings.shutdownMillis());
        lanes.values().forEach(l->{
            if(l.pool!=null) l.pool.shutdown();
            l.workers.forEach(LockSupport::unpark);
        });
        boolean interrupted=false;
        for(var lane:lanes.values()) {
            if(lane.pool==null) continue;
            long remaining=Math.max(0,deadline-System.nanoTime());
            try { lane.pool.awaitTermination(remaining,TimeUnit.NANOSECONDS); }
            catch(InterruptedException stopInterrupted) { interrupted=true; break; }
        }
        lanes.values().forEach(l->{ if(l.pool!=null && !l.pool.isTerminated()) l.pool.shutdownNow(); });
        if(interrupted) Thread.currentThread().interrupt();
    }

    /** Spring关闭回调只在停止接收并完成有界等待之后执行，不能保证强杀不响应中断的渠道。 */
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    /** 表示是否接受新轮询，不代表所有在途网络已结束。 */
    @Override public boolean isRunning() { return running; }
    /** 仅配置显式创建此Bean时自动启动；默认API上下文不创建运行时。 */
    @Override public boolean isAutoStartup() { return true; }
    /** 在普通基础设施销毁前启动关闭阶段，保留在途事务使用数据源的机会。 */
    @Override public int getPhase() { return Integer.MAX_VALUE-1024; }
    /** 手工测试与容器销毁使用同一有界关闭协议。 */
    @Override public void close() { stop(); }

    /** 无租户/订单标签的线程安全统计，handled是批次返回的任务数而非资金成功数。 */
    public Statistics statistics(JobType type) {
        Lane lane=lanes.get(Objects.requireNonNull(type));
        return new Statistics(lane.polls.sum(),lane.handled.sum(),lane.errors.sum(),lane.elapsedNanos.sum(),lane.active.get());
    }

    /** 每次调用最多领取处理一个任务，具体业务完成仍受原Worker租约约束。 */
    @FunctionalInterface public interface Batch {
        /** 返回0或1表示本批处理数量，不能据此解释支付终态。 */
        int run(long tenant,String owner);
    }

    /** 固定容量配置，不用无界队列吸收超出数据库或渠道处理能力的流量。 */
    public record Settings(int callbackThreads,int paymentThreads,int orderThreads,int refundCallbackThreads,int refundRecoveryThreads,int compensationThreads,int outboxThreads,int queryThreads,long idleMillis,long shutdownMillis) {
        /** 原七通道配置保持查询默认关闭，新增容量纳入同一总上限。 */
        public Settings(int callbackThreads,int paymentThreads,int orderThreads,int refundCallbackThreads,int refundRecoveryThreads,int compensationThreads,int outboxThreads,long idleMillis,long shutdownMillis) {
            this(callbackThreads,paymentThreads,orderThreads,refundCallbackThreads,refundRecoveryThreads,compensationThreads,outboxThreads,0,idleMillis,shutdownMillis);
        }
        /** 原六通道配置保持兼容；发布必须单独显式启用。 */
        public Settings(int callbackThreads,int paymentThreads,int orderThreads,int refundCallbackThreads,int refundRecoveryThreads,int compensationThreads,long idleMillis,long shutdownMillis) {
            this(callbackThreads,paymentThreads,orderThreads,refundCallbackThreads,refundRecoveryThreads,compensationThreads,0,idleMillis,shutdownMillis);
        }
        /** 原五通道配置保持兼容，新增本地补偿通道默认单槽位。 */
        public Settings(int callbackThreads,int paymentThreads,int orderThreads,int refundCallbackThreads,int refundRecoveryThreads,long idleMillis,long shutdownMillis) {
            this(callbackThreads,paymentThreads,orderThreads,refundCallbackThreads,refundRecoveryThreads,1,idleMillis,shutdownMillis);
        }
        /** 保持既有调用配置兼容，新增退款证据通道默认单槽位。 */
        public Settings(int callbackThreads,int paymentThreads,int orderThreads,long idleMillis,long shutdownMillis) {
            this(callbackThreads,paymentThreads,orderThreads,1,1,idleMillis,shutdownMillis);
        }
        /** 四通道配置升级默认增加一个退款恢复槽位，仍受全局容量上限约束。 */
        public Settings(int callbackThreads,int paymentThreads,int orderThreads,int refundCallbackThreads,long idleMillis,long shutdownMillis) {
            this(callbackThreads,paymentThreads,orderThreads,refundCallbackThreads,1,idleMillis,shutdownMillis);
        }
        /** 防止误配置无界线程、紧循环或无限停机等待。 */
        public Settings {
            if(callbackThreads<1 || paymentThreads<1 || orderThreads<1 || callbackThreads>8 || paymentThreads>8 || orderThreads>8
                || refundCallbackThreads<1 || refundCallbackThreads>8
                || refundRecoveryThreads<1 || refundRecoveryThreads>8
                || compensationThreads<1 || compensationThreads>8
                || outboxThreads<0 || outboxThreads>8 || queryThreads<0 || queryThreads>8
                || callbackThreads+paymentThreads+orderThreads+refundCallbackThreads+refundRecoveryThreads+compensationThreads+outboxThreads+queryThreads>16 || idleMillis<50 || idleMillis>60000
                || shutdownMillis<100 || shutdownMillis>30000)
                throw new IllegalArgumentException("后台执行容量或时间配置无效");
        }
        /** 每个类型只使用自己的容量，不借慢网络占用回调线程。 */
        public int threads(JobType type) {
            return switch(type) { case PAYMENT_CALLBACK->callbackThreads; case PAYMENT_RECOVER->paymentThreads; case ORDER_RESOLVE->orderThreads; case REFUND_CALLBACK->refundCallbackThreads; case REFUND_RECOVER->refundRecoveryThreads; case REFUND_COMPENSATE->compensationThreads; case OUTBOX_PUBLISH->outboxThreads; case RECON_QUERY->queryThreads; };
        }
    }

    /** 累计轮询、处理数量和错误为进程内指标，不替代持久账本或消息投递证据。 */
    public record Statistics(long polls,long handled,long errors,long elapsedNanos,int active) {}

    /** 前一轮没有完成处理的信号与租户分开，避免把首个租户为空误判为整轮空闲。 */
    private record Visit(OptionalLong tenant,boolean idleRound) {}

    /** 各通道独立游标与线程池，发现串行推进，业务执行不持游标锁。 */
    private final class Lane {
        private final JobType type;
        private long cursor;
        private long handledAtLastWrap;
        private ThreadPoolExecutor pool;
        private final List<Thread> workers=new CopyOnWriteArrayList<>();
        private final LongAdder polls=new LongAdder(),handled=new LongAdder(),errors=new LongAdder(),elapsedNanos=new LongAdder();
        private final AtomicInteger active=new AtomicInteger();
        private Lane(JobType type) { this.type=type; }
        private synchronized Visit next() {
            var next=discovery.next(type,cursor);
            boolean wrapped=next.isEmpty();
            long completed=handled.sum();
            boolean idleRound=wrapped && completed==handledAtLastWrap;
            // 多槽位共享轮转基线；仍在执行的任务不算完成，迟到完成将在下一轮抑制空闲等待。
            if(wrapped) handledAtLastWrap=completed;
            if(wrapped && cursor!=0) { cursor=0; next=discovery.next(type,0); }
            if(next.isPresent()) cursor=next.getAsLong();
            return new Visit(next,idleRound);
        }
    }
}
