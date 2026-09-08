package com.lrj.trade.server;
import com.lrj.trade.core.OutboxQueueSnapshotPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
/** worker独立观测持久发布积压，发布开关关闭也不能隐藏已有隔离任务。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="trade.worker.enabled",havingValue="true")
public class OutboxObservationConfiguration {
    /** 有界周期采样与Kafka发送分离，默认15秒，不在Prometheus抓取期间访问数据库。 */
    @Bean(destroyMethod="close")
    public OutboxQueueObservation outboxQueueObservation(OutboxQueueSnapshotPort source,MeterRegistry meters,
        @Value("${trade.outbox.observation-interval-millis:15000}") long interval) {
        return new OutboxQueueObservation(source,meters,interval);
    }
}
