package com.lrj.trade.server;
import com.lrj.trade.core.FinancialSnapshotPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
/** 仅worker启用资金积压采样，普通API抓取指标不查询数据库。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="trade.worker.enabled",havingValue="true")
public class FinancialObservationConfiguration {
    /** 沿用独立后台采样预算，不依赖支付渠道或业务发布开关。 */
    @Bean(destroyMethod="close")
    public FinancialObservation financialObservation(List<FinancialSnapshotPort> sources,MeterRegistry meters,Clock clock,
        @Value("${trade.financial-observation.interval-millis:15000}") long interval) {
        return new FinancialObservation(sources,meters,clock,interval);
    }
}
