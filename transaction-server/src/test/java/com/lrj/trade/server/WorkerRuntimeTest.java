package com.lrj.trade.server;

import com.lrj.trade.core.JobType;
import com.lrj.trade.jobs.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用可控制处理器检验生命周期和调度隔离，不冒充真实渠道或容量验收。 */
class WorkerRuntimeTest {
    private WorkerRuntime.Settings settings() { return new WorkerRuntime.Settings(1,1,1,50,1000); }
    private EnumMap<JobType,WorkerRuntime.Batch> batches() {
        var map=new EnumMap<JobType,WorkerRuntime.Batch>(JobType.class);
        for(var type:JobType.values()) map.put(type,(tenant,owner)->0);
        return map;
    }
    private JobTenantDiscovery discovery(int tenants) {
        var source=mock(JobTenantDiscovery.class);
        when(source.next(any(),anyLong())).thenAnswer(call->{
            long after=call.getArgument(1);
            return after<tenants?OptionalLong.of(after+1):OptionalLong.empty();
        });
        return source;
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(),"有界等待内未满足条件");
    }

    @Test void defaultConfigurationDoesNotCreateOrStartWorker() {
        new ApplicationContextRunner().withUserConfiguration(WorkerRuntimeConfiguration.class)
            .run(context->assertTrue(context.getBeansOfType(WorkerRuntime.class).isEmpty()));
    }

    @Test void explicitConfigurationStartsAllTypesAndRegistersLowCardinalityMetrics() {
        var source=discovery(1); var callbacks=mock(PaymentCallbackWorker.class);
        var payments=mock(PaymentRecoveryWorker.class); var orders=mock(OrderResolutionWorker.class);
        var refunds=mock(RefundCallbackWorker.class); var refundStarted=new CountDownLatch(1);
        var refundRecovery=mock(RefundRecoveryWorker.class); var recoveryStarted=new CountDownLatch(1);
        var compensation=mock(RefundCompensationWorker.class); var compensationStarted=new CountDownLatch(1);
        when(compensation.runBatch(anyLong(),anyString(),eq(1))).thenAnswer(c->{ compensationStarted.countDown(); return List.of(); });
        when(refundRecovery.runBatch(anyLong(),anyString(),eq(1))).thenAnswer(c->{ recoveryStarted.countDown(); return List.of(); });
        when(refunds.runBatch(anyLong(),anyString(),eq(1))).thenAnswer(c->{ refundStarted.countDown(); return List.of(); });
        var callbackStarted=new CountDownLatch(1); var paymentStarted=new CountDownLatch(1); var orderStarted=new CountDownLatch(1);
        when(callbacks.runBatch(anyLong(),anyString(),eq(1))).thenAnswer(c->{ callbackStarted.countDown(); return List.of(); });
        when(payments.runBatch(anyLong(),anyString(),eq(1))).thenAnswer(c->{ paymentStarted.countDown(); return List.of(); });
        when(orders.runBatch(anyLong(),anyString(),eq(1))).thenAnswer(c->{ orderStarted.countDown(); return List.of(); });
        var meters=new SimpleMeterRegistry();
        new ApplicationContextRunner().withUserConfiguration(WorkerRuntimeConfiguration.class)
            .withPropertyValues("trade.worker.enabled=true","trade.worker.callback-threads=1","trade.worker.idle-millis=50")
            .withBean(JobTenantDiscovery.class,()->source).withBean(PaymentCallbackWorker.class,()->callbacks)
            .withBean(PaymentRecoveryWorker.class,()->payments).withBean(OrderResolutionWorker.class,()->orders)
            .withBean(RefundCallbackWorker.class,()->refunds)
            .withBean(RefundRecoveryWorker.class,()->refundRecovery)
            .withBean(RefundCompensationWorker.class,()->compensation)
            .withBean(ReconciliationQueryWorker.class,()->mock(ReconciliationQueryWorker.class))
            .withBean(OutboxPublishWorker.class,()->mock(OutboxPublishWorker.class))
            .withBean(com.lrj.trade.core.EventPublisherPort.class,()->mock(com.lrj.trade.core.EventPublisherPort.class))
            .withBean(io.micrometer.core.instrument.MeterRegistry.class,()->meters).run(context->{
                assertNull(context.getStartupFailure()); assertTrue(callbackStarted.await(5,TimeUnit.SECONDS));
                assertTrue(paymentStarted.await(5,TimeUnit.SECONDS)); assertTrue(orderStarted.await(5,TimeUnit.SECONDS));
                assertTrue(refundStarted.await(5,TimeUnit.SECONDS));
                assertTrue(recoveryStarted.await(5,TimeUnit.SECONDS));
                assertTrue(compensationStarted.await(5,TimeUnit.SECONDS));
                assertTrue(context.getBean(WorkerRuntime.class).isRunning());
                assertEquals(1,meters.get("trade.worker.running").gauge().value());
                assertTrue(meters.getMeters().stream().flatMap(m->m.getId().getTags().stream())
                    .allMatch(tag->tag.getKey().equals("job_type")));
            });
        verify(callbacks,atLeastOnce()).runBatch(eq(1L),anyString(),eq(1));
        verify(source,never()).next(eq(JobType.OUTBOX_PUBLISH),anyLong());
        verify(source,never()).next(eq(JobType.RECON_QUERY),anyLong());
    }

    @Test void manyFutureTenantsDoNotAddOneIdleDelayPerTenant() throws Exception {
        var source=discovery(1001); var map=batches(); var reached=new CountDownLatch(1);
        map.put(JobType.PAYMENT_CALLBACK,(tenant,owner)->{ if(tenant==1001) reached.countDown(); return 0; });
        try(var runtime=new WorkerRuntime(source,map,new WorkerRuntime.Settings(1,1,1,250,1000))) {
            runtime.start();
            assertTrue(reached.await(5,TimeUnit.SECONDS),"不应由1000个空租户累加250秒等待");
        }
    }

    @Test void hotTenantRotatesToOtherTenantsWithoutWaitingForItsQueueToDrain() throws Exception {
        var map=batches(); var seen=ConcurrentHashMap.<Long>newKeySet(); var reached=new CountDownLatch(2);
        map.put(JobType.PAYMENT_CALLBACK,(tenant,owner)->{
            if(seen.add(tenant)) reached.countDown(); return 1;
        });
        try(var runtime=new WorkerRuntime(discovery(2),map,settings())) {
            runtime.start(); assertTrue(reached.await(5,TimeUnit.SECONDS));
            assertEquals(Set.of(1L,2L),seen);
        }
    }

    @Test void futureFirstTenantDoesNotThrottleContinuouslyBusySecondTenant() throws Exception {
        var map=batches(); var processed=new CountDownLatch(20);
        map.put(JobType.PAYMENT_CALLBACK,(tenant,owner)->{
            if(tenant==1) return 0;
            processed.countDown(); return 1;
        });
        try(var runtime=new WorkerRuntime(discovery(2),map,new WorkerRuntime.Settings(1,1,1,1000,1000))) {
            runtime.start();
            assertTrue(processed.await(5,TimeUnit.SECONDS),"有持续工作的整轮不得因首个租户为空累计19秒等待");
        }
    }

    @Test void blockedChannelDoesNotBlockCallbacksAndStopDrainsAcceptedBatch() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var callback=new CountDownLatch(1);
        var map=batches();
        map.put(JobType.PAYMENT_RECOVER,(tenant,owner)->{
            entered.countDown();
            try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            return 1;
        });
        map.put(JobType.PAYMENT_CALLBACK,(tenant,owner)->{ callback.countDown(); return 0; });
        try(var runtime=new WorkerRuntime(discovery(1),map,settings()); var pool=Executors.newSingleThreadExecutor()) {
            runtime.start();
            assertTrue(entered.await(5,TimeUnit.SECONDS)); assertTrue(callback.await(5,TimeUnit.SECONDS));
            var stopped=pool.submit(()->{ runtime.stop(); }); await(()->!runtime.isRunning());
            release.countDown(); stopped.get(5,TimeUnit.SECONDS);
            for(var type:JobType.values()) assertEquals(0,runtime.statistics(type).active());
        } finally { release.countDown(); }
    }

    @Test void temporaryHandlerAndDiscoveryFailuresDoNotPermanentlyStopLoops() throws Exception {
        var source=discovery(1); var failDiscovery=new AtomicBoolean(true); var failHandler=new AtomicBoolean(true);
        when(source.next(eq(JobType.PAYMENT_CALLBACK),anyLong())).thenAnswer(c->{
            if(failDiscovery.getAndSet(false)) throw new IllegalStateException("敏感测试内容不得记录");
            return (long)c.getArgument(1)==0?OptionalLong.of(1):OptionalLong.empty();
        });
        var recovered=new CountDownLatch(1); var map=batches();
        map.put(JobType.PAYMENT_CALLBACK,(tenant,owner)->{
            if(failHandler.getAndSet(false)) throw new IllegalStateException("临时失败");
            recovered.countDown(); return 0;
        });
        try(var runtime=new WorkerRuntime(source,map,settings())) {
            runtime.start(); assertTrue(recovered.await(5,TimeUnit.SECONDS));
            assertEquals(2,runtime.statistics(JobType.PAYMENT_CALLBACK).errors());
        }
    }

    @Test void shutdownTimeoutDoesNotAllowReplacementThreadsWhileOldCallIsAlive() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var map=batches();
        map.put(JobType.PAYMENT_RECOVER,(tenant,owner)->{
            entered.countDown();
            boolean released=false;
            while(!released) try { release.await(); released=true; } catch(InterruptedException ignored) { /* 模拟错误适配器不响应中断。 */ }
            return 1;
        });
        var runtime=new WorkerRuntime(discovery(1),map,new WorkerRuntime.Settings(1,1,1,50,100));
        try {
            runtime.start(); assertTrue(entered.await(5,TimeUnit.SECONDS));
            runtime.stop(); assertFalse(runtime.isRunning());
            assertEquals(1,runtime.statistics(JobType.PAYMENT_RECOVER).active());
            assertThrows(IllegalStateException.class,runtime::start);
        } finally {
            release.countDown(); runtime.close();
        }
        await(()->runtime.statistics(JobType.PAYMENT_RECOVER).active()==0);
    }

    @Test void stoppingDuringDiscoveryDoesNotInvokeAnotherBusinessBatch() throws Exception {
        var source=discovery(1); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(source.next(eq(JobType.PAYMENT_RECOVER),anyLong())).thenAnswer(c->{
            entered.countDown(); release.await(); return OptionalLong.of(1);
        });
        var invoked=new AtomicInteger(); var map=batches();
        map.put(JobType.PAYMENT_RECOVER,(tenant,owner)->{ invoked.incrementAndGet(); return 1; });
        try(var runtime=new WorkerRuntime(source,map,settings()); var pool=Executors.newSingleThreadExecutor()) {
            runtime.start(); assertTrue(entered.await(5,TimeUnit.SECONDS));
            var stop=pool.submit(()->{ runtime.stop(); }); await(()->!runtime.isRunning()); release.countDown(); stop.get(5,TimeUnit.SECONDS);
            assertEquals(0,invoked.get());
        } finally { release.countDown(); }
    }

    @Test void invalidCapacityOrMissingTaskTypeCannotStart() {
        assertThrows(IllegalArgumentException.class,()->new WorkerRuntime.Settings(1,1,1,1,1,1,3,8,50,1000));
        assertThrows(IllegalArgumentException.class,()->new WorkerRuntime.Settings(1,1,1,1,1,1,0,-1,50,1000));
        assertThrows(IllegalArgumentException.class,()->new WorkerRuntime.Settings(9,1,1,50,1000));
        assertThrows(IllegalArgumentException.class,()->new WorkerRuntime.Settings(1,1,1,0,1000));
        assertThrows(IllegalArgumentException.class,()->new WorkerRuntime.Settings(1,1,1,50,30001));
        assertThrows(IllegalArgumentException.class,()->new WorkerRuntime(discovery(1),Map.of(),settings()));
    }
}
