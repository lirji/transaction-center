package com.lrj.trade.server;
import com.lrj.trade.core.OutboxMaintenancePort;
import com.lrj.trade.jobs.OutboxMaintenanceRuntime;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
/** 维护角色双重显式开启；普通API不运行跨租户扫描。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"trade.worker.enabled","trade.outbox.maintenance-enabled"},havingValue="true")
public class OutboxMaintenanceConfiguration {
    /** 所有指标无租户或事件标签；数据库积压由独立持久观测补齐。 */
    @Bean(destroyMethod="close")
    public OutboxMaintenanceRuntime outboxMaintenanceRuntime(OutboxMaintenancePort maintenance,MeterRegistry meters,
        @Value("${trade.outbox.maintenance-page-size:100}") int pageSize,
        @Value("${trade.outbox.maintenance-interval-millis:300000}") long intervalMillis,
        @Value("${trade.outbox.maintenance-tick-millis:200}") long tickMillis) {
        var runtime=new OutboxMaintenanceRuntime(maintenance,pageSize,intervalMillis,tickMillis);
        FunctionCounter.builder("trade.outbox.maintenance.polls",runtime,r->r.statistics().polls()).register(meters);
        FunctionCounter.builder("trade.outbox.maintenance.pages",runtime,r->r.statistics().pages()).register(meters);
        FunctionCounter.builder("trade.outbox.maintenance.scanned",runtime,r->r.statistics().scanned()).register(meters);
        FunctionCounter.builder("trade.outbox.maintenance.candidates",runtime,r->r.statistics().candidates()).register(meters);
        FunctionCounter.builder("trade.outbox.maintenance.rounds",runtime,r->r.statistics().rounds()).register(meters);
        FunctionCounter.builder("trade.outbox.maintenance.errors",runtime,r->r.statistics().errors()).register(meters);
        Gauge.builder("trade.outbox.maintenance.last.poll.seconds",runtime,r->r.statistics().lastPollMillis()/1000.0).register(meters);
        Gauge.builder("trade.outbox.maintenance.running",runtime,r->r.isRunning()?1:0).register(meters);
        return runtime;
    }
}
