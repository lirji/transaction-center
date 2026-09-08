package com.lrj.trade.server;

import com.lrj.trade.core.*;
import com.lrj.trade.jobs.OutboxMaintenanceRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证后台维护容量、角色开关和观测故障，避免监控失败被当成零积压。 */
class OutboxMaintenanceRuntimeTest {
    @Test void defaultApiAndWorkerAloneDoNotEnableMaintenance() {
        new ApplicationContextRunner().withUserConfiguration(OutboxMaintenanceConfiguration.class,OutboxObservationConfiguration.class)
            .run(c->{assertTrue(c.getBeansOfType(OutboxMaintenanceRuntime.class).isEmpty());assertTrue(c.getBeansOfType(OutboxQueueObservation.class).isEmpty());});
        new ApplicationContextRunner().withUserConfiguration(OutboxMaintenanceConfiguration.class)
            .withPropertyValues("trade.worker.enabled=true")
            .run(c->assertTrue(c.getBeansOfType(OutboxMaintenanceRuntime.class).isEmpty()));
    }
    @Test void maintenanceVisitsTenantsFairlyAndStopsDiscovery() throws Exception {
        var port=mock(OutboxMaintenancePort.class);
        when(port.nextTenant(anyLong())).thenAnswer(c->{long cursor=c.getArgument(0);return cursor<2?OptionalLong.of(cursor+1):OptionalLong.empty();});
        var visited=new CopyOnWriteArrayList<Long>();var finished=new CountDownLatch(3);
        when(port.scanPage(anyLong(),eq(10),eq(60000L))).thenAnswer(c->{visited.add(c.getArgument(0));finished.countDown();return new OutboxMaintenancePort.Step(true,10,2,false);});
        try(var runtime=new OutboxMaintenanceRuntime(port,10,60000,50)) {
            runtime.start();assertTrue(finished.await(5,TimeUnit.SECONDS));runtime.stop();
            assertEquals(List.of(1L,2L,1L),visited.subList(0,3));
            assertFalse(runtime.isRunning());assertTrue(runtime.statistics().pages()>=3);
            clearInvocations(port);Thread.sleep(100);verifyNoInteractions(port);
        }
    }
    @Test void observationRetainsLastSampleAndScrapesNeverQueryDatabase() {
        var source=mock(OutboxQueueSnapshotPort.class);var now=Instant.now();
        var sample=new OutboxQueueSnapshotPort.Snapshot(now,List.of(
            new OutboxQueueSnapshotPort.State("READY",10001,now.minusSeconds(90)),
            new OutboxQueueSnapshotPort.State("RUNNING",0,null),new OutboxQueueSnapshotPort.State("REVIEW",2,now.minusSeconds(60))));
        when(source.snapshot()).thenThrow(new IllegalStateException("unavailable")).thenReturn(sample).thenThrow(new IllegalStateException("unavailable"));
        var meters=new SimpleMeterRegistry();
        try(var observer=new OutboxQueueObservation(source,meters,5000)) {
            var ready=meters.get("trade.outbox.queue.count.lower.bound").tag("status","READY").gauge();
            assertTrue(Double.isNaN(ready.value()));observer.refresh();assertTrue(Double.isNaN(ready.value()));
            assertEquals(0,meters.get("trade.outbox.queue.observed.at.seconds").gauge().value());
            observer.refresh();assertEquals(10001,ready.value());observer.refresh();assertEquals(10001,ready.value());
            assertEquals(2,meters.get("trade.outbox.queue.observation.failures").functionCounter().count());
            assertEquals(now.toEpochMilli()/1000.0,meters.get("trade.outbox.queue.observed.at.seconds").gauge().value());
            assertTrue(meters.get("trade.outbox.queue.oldest.age.seconds").tag("status","READY").gauge().value()>=90);
            verify(source,times(3)).snapshot();
        }
    }
}
