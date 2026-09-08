package com.lrj.trade.server;

import com.lrj.trade.core.AutomaticAdmissionGate;
import com.lrj.trade.core.OutboxQueueSnapshotPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 每个API实例明确启用后独立采样，不能依赖只有worker进程拥有的内存指标。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="trade.automatic-admission.enabled",havingValue="true")
public class AutomaticAdmissionConfiguration {
    /** 存储卷必须由部署方核对；默认不开启，避免把未知磁盘当成健康。 */
    @Bean(destroyMethod="close")
    public AutomaticAdmissionSampler automaticAdmissionSampler(AutomaticAdmissionGate gate,OutboxQueueSnapshotPort queue,
            Clock clock,MeterRegistry meters,
            @Value("${trade.automatic-admission.disk-path:}") String path,
            @Value("${trade.automatic-admission.disk-store:}") String store,
            @Value("${trade.automatic-admission.backlog-high:10000}") long high,
            @Value("${trade.automatic-admission.backlog-low:5000}") long low,
            @Value("${trade.automatic-admission.disk-min-bytes:5368709120}") long min,
            @Value("${trade.automatic-admission.disk-recovery-bytes:10737418240}") long recovery,
            @Value("${trade.automatic-admission.interval-millis:15000}") long interval,
            @Value("${trade.automatic-admission.max-age-millis:45000}") long maxAge) {
        if(maxAge<=interval) throw new IllegalArgumentException("样本有效期必须大于采样周期");
        return new AutomaticAdmissionSampler(gate,queue,new DatabaseDiskSpace(path,store),clock,meters,high,low,min,recovery,interval);
    }
}
