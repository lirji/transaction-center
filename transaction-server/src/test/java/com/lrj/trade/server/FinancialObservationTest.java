package com.lrj.trade.server;
import com.lrj.trade.core.FinancialSnapshotPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 失败样本和各域新鲜度是告警正确性的边界。 */
class FinancialObservationTest {
    @Test void domainFailureKeepsPriorValueAndDoesNotHideOtherDomainProgress() {
        var payment=mock(FinancialSnapshotPort.class);var refund=mock(FinancialSnapshotPort.class);
        when(payment.domain()).thenReturn("payment");when(refund.domain()).thenReturn("refund");
        when(payment.keys()).thenReturn(List.of("payment_unknown"));when(refund.keys()).thenReturn(List.of("callback_pending"));
        Instant now=Instant.parse("2026-01-01T00:01:00Z");
        var a=new FinancialSnapshotPort.Snapshot(List.of(new FinancialSnapshotPort.Row("payment_unknown",2,now.minusSeconds(60),now.minusSeconds(10))));
        var b=new FinancialSnapshotPort.Snapshot(List.of(new FinancialSnapshotPort.Row("callback_pending",0,null,now)));
        when(payment.snapshot()).thenThrow(new IllegalStateException()).thenReturn(a).thenThrow(new IllegalStateException()).thenReturn(new FinancialSnapshotPort.Snapshot(List.of()));
        when(refund.snapshot()).thenReturn(b);
        var meters=new SimpleMeterRegistry();
        try(var observer=new FinancialObservation(List.of(payment,refund),meters,Clock.fixed(now,ZoneOffset.UTC),5000)) {
            observer.refresh();
            assertTrue(Double.isNaN(meters.get("trade.financial.backlog.count.lower.bound").tags("domain","payment").gauge().value()));
            assertEquals(0,meters.get("trade.financial.observed.at.seconds").tag("domain","payment").gauge().value());
            assertEquals(now.toEpochMilli()/1000.0,meters.get("trade.financial.observed.at.seconds").tag("domain","refund").gauge().value());
            observer.refresh();observer.refresh();observer.refresh();
            assertEquals(2,meters.get("trade.financial.backlog.count.lower.bound").tag("domain","payment").gauge().value());
            assertEquals(60,meters.get("trade.financial.backlog.oldest.age.seconds").tag("domain","payment").gauge().value());
            assertEquals(now.minusSeconds(10).toEpochMilli()/1000.0,meters.get("trade.financial.observed.at.seconds").tag("domain","payment").gauge().value());
            assertEquals(3,meters.get("trade.financial.observation.failures").tag("domain","payment").functionCounter().count());
            assertEquals(0,meters.get("trade.financial.backlog.oldest.age.seconds").tag("domain","refund").gauge().value());
        }
    }
    @Test void lifecycleHasOneWorkerAndCanStopAndRestart() throws Exception {
        var source=mock(FinancialSnapshotPort.class);when(source.domain()).thenReturn("payment");when(source.keys()).thenReturn(List.of("payment_unknown"));
        var sampled=new java.util.concurrent.CountDownLatch(1);
        when(source.snapshot()).thenAnswer(invocation->{sampled.countDown();return new FinancialSnapshotPort.Snapshot(List.of(new FinancialSnapshotPort.Row("payment_unknown",0,null,Instant.now())));});
        try(var observer=new FinancialObservation(List.of(source),new SimpleMeterRegistry(),Clock.systemUTC(),5000)) {
            observer.start();observer.start();assertTrue(sampled.await(2,java.util.concurrent.TimeUnit.SECONDS));observer.stop();assertFalse(observer.isRunning());
            observer.start();assertTrue(observer.isRunning());observer.stop();
        }
    }
}
