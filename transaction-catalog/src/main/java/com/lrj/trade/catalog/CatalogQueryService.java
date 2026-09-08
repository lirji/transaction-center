package com.lrj.trade.catalog;
import com.lrj.trade.core.*;
import com.lrj.trade.catalog.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** 有界单飞与独立主库预算保护展示缓存失效时的交易数据库。 */
@Service
public class CatalogQueryService {
    private final CatalogCachePort cache;private final CatalogQueryLoader loader;private final Semaphore flights,reads;private final ConcurrentHashMap<Flight,CompletableFuture<CatalogCachePort.Value>> pending=new ConcurrentHashMap<>();private final long waitMillis;private final int rate;private double tokens;private long tick=System.nanoTime();
    private final AtomicLong negativeHits=new AtomicLong(),hits=new AtomicLong(),loads=new AtomicLong(),rejected=new AtomicLong(),joined=new AtomicLong();
    /** 预算按实例配置，主库并发默认仅占连接池少量容量。 */
    public CatalogQueryService(CatalogCachePort cache,CatalogQueryLoader loader,@Value("${trade.catalog.query-max-keys:1024}") int maxKeys,@Value("${trade.catalog.query-db-concurrency:2}") int concurrency,@Value("${trade.catalog.query-db-rate:64}") int rate,@Value("${trade.catalog.query-wait-ms:150}") long waitMillis) {
        if(maxKeys<1 || maxKeys>65536 || concurrency<1 || concurrency>8 || rate<1 || rate>10000 || waitMillis<1 || waitMillis>1000) throw new IllegalArgumentException("商品展示回源预算无效");this.cache=cache;this.loader=loader;flights=new Semaphore(maxKeys);reads=new Semaphore(concurrency);this.rate=rate;this.tokens=rate;this.waitMillis=waitMillis;
    }
    /** 会员商品页只返回展示数据，后续报价仍走原主库。 */
    @Transactional(propagation=Propagation.NEVER)
    public List<Product> products(long tenant,long after,int limit) {return query(new CatalogCachePort.Query(tenant,CatalogCachePort.Kind.PRODUCTS,0,after,limit)).products();}
    /** 不存在商品采用短负缓存，跨租户也不能看见其存在性。 */
    @Transactional(propagation=Propagation.NEVER)
    public Product product(long tenant,long product) {return query(new CatalogCachePort.Query(tenant,CatalogCachePort.Kind.PRODUCT,product,0,1)).products().stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","商品不存在或不可售"));}
    /** 发布SKU展示列表同样有界缓存，管理员草稿不经过此入口。 */
    @Transactional(propagation=Propagation.NEVER)
    public List<Sku> skus(long tenant,long product,long after,int limit) {return query(new CatalogCachePort.Query(tenant,CatalogCachePort.Kind.SKUS,product,after,limit)).skus();}
    private CatalogCachePort.Value query(CatalogCachePort.Query query) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("商品展示缓存不能加入业务事务");
        CatalogCachePort.Probe probe;
        try {probe=cache.lookup(query);if(probe.value()!=null) {query.validate(probe.value());hits.incrementAndGet();if(probe.value().empty()) negativeHits.incrementAndGet();return probe.value();}}
        catch(RuntimeException invalidCache) {probe=new CatalogCachePort.Probe(null,null,false);}
        Flight key=new Flight(query,probe.available()?probe.generation():"DEGRADED");var existing=pending.get(key);if(existing!=null) return await(existing);
        if(!flights.tryAcquire()) throw busy();var created=new CompletableFuture<CatalogCachePort.Value>();existing=pending.putIfAbsent(key,created);if(existing!=null) {flights.release();return await(existing);}
        try {
            if(!reads.tryAcquire()) throw busy();CatalogCachePort.Value result;
            try {if(!ratePermit()) throw busy();loads.incrementAndGet();result=loader.load(query);query.validate(result);} finally {reads.release();}
            if(probe.available()) {try {cache.store(query,probe.generation(),result);}catch(RuntimeException cacheUnavailable) { /* 展示缓存写失败不能丢失已经完成的主库查询结果。 */ }}created.complete(result);return result;
        } catch(RuntimeException | Error failure) {created.completeExceptionally(failure);throw failure;}
        finally {pending.remove(key,created);flights.release();}
    }
    private CatalogCachePort.Value await(CompletableFuture<CatalogCachePort.Value> future) {
        joined.incrementAndGet();try {return future.get(waitMillis,TimeUnit.MILLISECONDS);}catch(InterruptedException error) {Thread.currentThread().interrupt();throw busy();}catch(TimeoutException error) {throw busy();}catch(ExecutionException error) {if(error.getCause() instanceof RuntimeException runtime) throw runtime;throw new TradeException("CATALOG_QUERY_UNAVAILABLE","商品展示暂不可用");}
    }
    private synchronized boolean ratePermit() {long now=System.nanoTime();tokens=Math.min(rate,tokens+(now-tick)/1_000_000_000d*rate);tick=now;if(tokens<1) return false;tokens-=1;return true;}
    private TradeException busy() {rejected.incrementAndGet();return new TradeException("CATALOG_QUERY_BUSY","商品展示繁忙，请稍后重试");}
    /** 低基数累计指标，不将租户或请求键用于时序标签。 */
    public Statistics statistics() {return new Statistics(hits.get(),loads.get(),rejected.get(),joined.get(),pending.size(),negativeHits.get());}
    /** 等待者不会跨目录发布代次共享旧结果。 */
    private record Flight(CatalogCachePort.Query query,String generation) {}
    /** 观测只读快照，不从清理后的Map推测累计计数。 */
    public record Statistics(long hits,long loads,long rejected,long joined,int pendingKeys,long negativeHits) {}
}
