package com.lrj.trade.server;

import com.lrj.trade.core.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticAdmissionTest {
    private static final Instant NOW=Instant.parse("2026-09-08T00:00:00Z");
    private Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    private OutboxQueueSnapshotPort.Snapshot snapshot(long count) {
        return new OutboxQueueSnapshotPort.Snapshot(NOW,List.of(new OutboxQueueSnapshotPort.State("READY",count,null),
            new OutboxQueueSnapshotPort.State("RUNNING",0,null),new OutboxQueueSnapshotPort.State("REVIEW",0,null)));
    }
    @Test void gateFailsClosedOnStartupExpiryFutureAndPreservesManualPause() {
        var gate=new AutomaticAdmissionGate(true,clock,45000);assertTrue(gate.blocked());
        gate.publish(false,NOW);assertFalse(gate.blocked());
        assertFalse(gate.apply(new AdmissionPolicy(false,true,7)).orderEnabled());
        assertEquals(7,gate.apply(new AdmissionPolicy(false,true,7)).version());
        gate.publish(false,NOW.minusSeconds(45));assertTrue(gate.blocked());
        gate.publish(false,NOW.plusMillis(1));assertTrue(gate.blocked());
        assertFalse(new AutomaticAdmissionGate(false,clock,45000).blocked());
    }
    @Test void monotonicLifetimeAndExpiryLatchPreventClockRollbackFromReopening() {
        var wall=new AtomicReference<>(NOW);var tick=new AtomicLong();
        Clock dynamic=new Clock() {
            public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(java.time.ZoneId zone){return this;}
            public Instant instant(){return wall.get();}
        };
        var gate=new AutomaticAdmissionGate(true,dynamic,45000,tick::get);
        gate.publish(false,NOW);assertFalse(gate.blocked());
        tick.set(TimeUnit.SECONDS.toNanos(46));wall.set(NOW.plusSeconds(30));assertTrue(gate.blocked());
        wall.set(NOW.plusSeconds(1));assertTrue(gate.blocked());
        tick.set(0);wall.set(NOW);gate.publish(false,NOW);
        wall.set(NOW.plusSeconds(46));assertTrue(gate.blocked());
        wall.set(NOW.plusSeconds(1));assertTrue(gate.blocked());
        gate.publish(false,wall.get());assertFalse(gate.blocked());
    }
    @Test void staleLowWatermarkCannotPermitFreshMiddleWatermark() {
        var gate=new AutomaticAdmissionGate(true,clock,45000);
        var sample=new AtomicReference<>(snapshot(10));
        try(var sampler=new AutomaticAdmissionSampler(gate,sample::get,()->200,clock,new SimpleMeterRegistry(),10,5,100,150,5000)) {
            sampler.refresh();assertTrue(gate.blocked());
            sample.set(new OutboxQueueSnapshotPort.Snapshot(NOW.minusSeconds(46),snapshot(0).states()));
            sampler.refresh();assertTrue(gate.blocked());
            sample.set(snapshot(6));sampler.refresh();assertTrue(gate.blocked());
            sample.set(snapshot(5));sampler.refresh();assertFalse(gate.blocked());
        }
    }
    @Test void combinedSignalsUseHysteresisAndRecoverOnlyWithCompleteHealthySample() {
        var gate=new AutomaticAdmissionGate(true,clock,45000);var count=new AtomicLong(0);var disk=new AtomicLong(200);
        var source=new AtomicReference<OutboxQueueSnapshotPort>(() -> snapshot(count.get()));
        try(var sampler=new AutomaticAdmissionSampler(gate,()->source.get().snapshot(),disk::get,clock,new SimpleMeterRegistry(),10,5,100,150,5000)) {
            sampler.refresh();assertFalse(gate.blocked());
            count.set(10);sampler.refresh();assertTrue(gate.blocked());
            count.set(6);sampler.refresh();assertTrue(gate.blocked());
            count.set(5);sampler.refresh();assertFalse(gate.blocked());
            disk.set(99);sampler.refresh();assertTrue(gate.blocked());
            disk.set(149);sampler.refresh();assertTrue(gate.blocked());
            disk.set(150);sampler.refresh();assertFalse(gate.blocked());
            source.set(()->{throw new IllegalStateException("probe");});sampler.refresh();assertTrue(gate.blocked());
            source.set(()->snapshot(0));sampler.refresh();assertFalse(gate.blocked());
            source.set(()->new OutboxQueueSnapshotPort.Snapshot(NOW,List.of()));sampler.refresh();assertTrue(gate.blocked());
        }
        assertTrue(gate.blocked());
    }
    @Test void slowProbeCannotReopenAfterStopAndCannotQueueConcurrentRefreshes() throws Exception {
        var gate=new AutomaticAdmissionGate(true,clock,45000);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var reads=new AtomicInteger();
        var sampler=new AutomaticAdmissionSampler(gate,()->{reads.incrementAndGet();entered.countDown();try {release.await();} catch(InterruptedException e) {throw new IllegalStateException(e);} return snapshot(0);},()->200,clock,new SimpleMeterRegistry(),10,5,100,150,5000);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(sampler::refresh);assertTrue(entered.await(2,TimeUnit.SECONDS));
            sampler.refresh();assertEquals(1,reads.get());sampler.stop();release.countDown();future.get(2,TimeUnit.SECONDS);
            assertTrue(gate.blocked());sampler.refresh();assertEquals(1,reads.get());
        } finally {release.countDown();pool.shutdownNow();sampler.close();}
    }
    @Test void diskProbeRequiresExplicitMountAndRejectsIdentityMismatch() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new DatabaseDiskSpace("",""));
        assertThrows(IllegalArgumentException.class,()->new DatabaseDiskSpace("relative","store"));
        var path=Files.createTempDirectory("trade-disk-probe-");
        try {
            assertTrue(new DatabaseDiskSpace(path.toString(),Files.getFileStore(path).name()).getAsLong()>=0);
            assertThrows(IllegalStateException.class,()->new DatabaseDiskSpace(path.toString(),"not-the-mounted-store").getAsLong());
        } finally {Files.delete(path);}
    }
    @Test void thresholdsCannotExceedObservableCountsAndConfigurationRequiresMapping() {
        var gate=new AutomaticAdmissionGate(true,clock,45000);
        assertThrows(IllegalArgumentException.class,()->new AutomaticAdmissionSampler(gate,()->snapshot(0),()->200,clock,new SimpleMeterRegistry(),10002,5,100,150,5000));
        assertThrows(IllegalArgumentException.class,()->new AutomaticAdmissionConfiguration().automaticAdmissionSampler(gate,()->snapshot(0),clock,new SimpleMeterRegistry(),"","",10,5,100,150,5000,45000));
    }
}
