package com.lrj.trade.server;

import com.lrj.trade.core.JobType;
import com.lrj.trade.core.EventPublisherPort;
import com.lrj.trade.jobs.*;
import io.micrometer.core.instrument.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** 默认不创建后台执行器；必须显式启用角色，防止普通API启动就处理共享业务数据。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="trade.worker.enabled",havingValue="true")
public class WorkerRuntimeConfiguration {
    /** 固定八类公开工作者，退款恢复与证据分别使用独立通道，统计不含租户或资金标识。 */
    @Bean(destroyMethod="close")
    public WorkerRuntime workerRuntime(JobTenantDiscovery discovery,PaymentCallbackWorker callbacks,
        PaymentRecoveryWorker payments,OrderResolutionWorker orders,RefundCallbackWorker refunds,RefundRecoveryWorker refundRecovery,RefundCompensationWorker compensation,OutboxPublishWorker outbox,ReconciliationQueryWorker queries,EventPublisherPort publisher,MeterRegistry meters,
        @Value("${trade.worker.callback-threads:2}") int callbackThreads,
        @Value("${trade.worker.payment-threads:1}") int paymentThreads,
        @Value("${trade.worker.order-threads:1}") int orderThreads,
        @Value("${trade.worker.refund-callback-threads:1}") int refundCallbackThreads,
        @Value("${trade.worker.refund-recovery-threads:1}") int refundRecoveryThreads,
        @Value("${trade.worker.compensation-threads:1}") int compensationThreads,
        @Value("${trade.worker.outbox-threads:1}") int outboxThreads,
        @Value("${trade.worker.query-threads:1}") int queryThreads,
        @Value("${trade.worker.idle-millis:250}") long idleMillis,
        @Value("${trade.worker.shutdown-millis:30000}") long shutdownMillis) {
        var runtime=new WorkerRuntime(discovery,Map.of(
            JobType.PAYMENT_CALLBACK,(tenant,owner)->callbacks.runBatch(tenant,owner,1).size(),
            JobType.PAYMENT_RECOVER,(tenant,owner)->payments.runBatch(tenant,owner,1).size(),
            JobType.ORDER_RESOLVE,(tenant,owner)->orders.runBatch(tenant,owner,1).size(),
            JobType.REFUND_CALLBACK,(tenant,owner)->refunds.runBatch(tenant,owner,1).size(),
            JobType.REFUND_RECOVER,(tenant,owner)->refundRecovery.runBatch(tenant,owner,1).size(),
            JobType.REFUND_COMPENSATE,(tenant,owner)->compensation.runBatch(tenant,owner,1).size(),
            JobType.OUTBOX_PUBLISH,(tenant,owner)->outbox.runBatch(tenant,owner,1).size(),
            JobType.RECON_QUERY,(tenant,owner)->queries.runBatch(tenant,owner,1).size()),
            new WorkerRuntime.Settings(callbackThreads,paymentThreads,orderThreads,refundCallbackThreads,refundRecoveryThreads,compensationThreads,publisher.enabled()?outboxThreads:0,queries.enabled()?queryThreads:0,idleMillis,shutdownMillis));
        for(var type:JobType.values()) {
            FunctionCounter.builder("trade.worker.polls",runtime,r->r.statistics(type).polls()).tag("job_type",type.name()).register(meters);
            FunctionCounter.builder("trade.worker.handled",runtime,r->r.statistics(type).handled()).tag("job_type",type.name()).register(meters);
            FunctionCounter.builder("trade.worker.errors",runtime,r->r.statistics(type).errors()).tag("job_type",type.name()).register(meters);
            FunctionCounter.builder("trade.worker.elapsed",runtime,r->r.statistics(type).elapsedNanos()/1_000_000_000.0)
                .baseUnit("seconds").tag("job_type",type.name()).register(meters);
            Gauge.builder("trade.worker.active",runtime,r->r.statistics(type).active()).tag("job_type",type.name()).register(meters);
        }
        Gauge.builder("trade.worker.running",runtime,r->r.isRunning()?1:0).register(meters);
        return runtime;
    }
}
