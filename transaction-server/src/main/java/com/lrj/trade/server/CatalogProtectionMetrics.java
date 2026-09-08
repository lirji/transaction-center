package com.lrj.trade.server;
import com.lrj.trade.catalog.CatalogQueryService;
import com.lrj.trade.inventory.HotSkuAdmission;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.*;
/** 商品读预算与热点拒绝使用固定低基数指标，避免请求键放大时序数量。 */
@Configuration
public class CatalogProtectionMetrics {
    /** 计数来自服务生命周期累计值，瞬时队列和键数使用Gauge。 */
    @Bean
    public MeterBinder catalogProtectionBinder(CatalogQueryService query,RedisCatalogCache cache,HotSkuAdmission hotSku) {
        return registry->{
            FunctionCounter.builder("trade.catalog.cache.hits",query,q->q.statistics().hits()).register(registry);
            FunctionCounter.builder("trade.catalog.cache.negative.hits",query,q->q.statistics().negativeHits()).register(registry);
            FunctionCounter.builder("trade.catalog.database.loads",query,q->q.statistics().loads()).register(registry);
            FunctionCounter.builder("trade.catalog.query.rejected",query,q->q.statistics().rejected()).register(registry);
            FunctionCounter.builder("trade.catalog.singleflight.joined",query,q->q.statistics().joined()).register(registry);
            Gauge.builder("trade.catalog.singleflight.keys",query,q->q.statistics().pendingKeys()).register(registry);
            FunctionCounter.builder("trade.catalog.redis.failures",cache,c->c.statistics().failures()).register(registry);
            FunctionCounter.builder("trade.catalog.cache.invalidations",cache,c->c.statistics().invalidations()).register(registry);
            FunctionCounter.builder("trade.catalog.redis.rejected",cache,c->c.statistics().rejected()).register(registry);
            FunctionCounter.builder("trade.inventory.hot.sku.rejected",hotSku,HotSkuAdmission::rejectedCount).register(registry);
            Gauge.builder("trade.inventory.hot.sku.keys",hotSku,HotSkuAdmission::trackedKeys).register(registry);
        };
    }
}
