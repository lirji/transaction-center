package com.lrj.trade.server;
import com.lrj.trade.catalog.*;
import com.lrj.trade.catalog.model.Product;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
/** 显式运行的dev_infra验证，仅使用临时ACL及随机环境前缀，缓存键由TTL清理。 */
@EnabledIfEnvironmentVariable(named="TRADE_CATALOG_IT_USER",matches=".+")
class RedisCatalogCacheIT {
    @Test void realRedisGenerationNegativeCacheIsolationAndCommitOnlyInvalidation() throws Exception {
        String environment=System.getenv("TRADE_CATALOG_IT_ENV"),user=System.getenv("TRADE_CATALOG_IT_USER"),password=System.getenv("TRADE_CATALOG_IT_PASSWORD");
        try(var cache=new RedisCatalogCache(true,environment,"127.0.0.1",46379,user,password,8,100)) {
            var query=new CatalogCachePort.Query(1,CatalogCachePort.Kind.PRODUCT,1,0,1);var first=cache.lookup(query);assertTrue(first.available());assertNull(first.value());
            var value=new CatalogCachePort.Value(List.of(new Product("1","1","商品","PUBLISHED",1,100,"CNY",1L)),List.of());cache.store(query,first.generation(),value);assertEquals(value,cache.lookup(query).value());
            assertNull(cache.lookup(new CatalogCachePort.Query(2,CatalogCachePort.Kind.PRODUCT,1,0,1)).value());
            var absent=new CatalogCachePort.Query(1,CatalogCachePort.Kind.PRODUCT,2,0,1);var missing=cache.lookup(absent);var empty=new CatalogCachePort.Value(List.of(),List.of());cache.store(absent,missing.generation(),empty);assertEquals(empty,cache.lookup(absent).value());
            TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
            try {cache.invalidateAfterCommit(1);for(var sync:TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);}
            finally {TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
            assertEquals(first.generation(),cache.lookup(query).generation());
            TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
            try {cache.invalidateAfterCommit(1);for(var sync:TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();}
            finally {TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);CatalogCachePort.Probe next;
            do {next=cache.lookup(query);if(!first.generation().equals(next.generation())) break;Thread.sleep(10);}while(System.nanoTime()<deadline);
            assertTrue(next.available());assertNotEquals(first.generation(),next.generation());assertNull(next.value());
            cache.store(query,first.generation(),value);assertNull(cache.lookup(query).value());
            assertEquals(1,cache.statistics().invalidations());
            var uri=io.lettuce.core.RedisURI.Builder.redis("127.0.0.1",46379).withAuthentication(user,password.toCharArray()).withTimeout(java.time.Duration.ofMillis(100)).build();
            var rawClient=io.lettuce.core.RedisClient.create(uri);
            try(var raw=rawClient.connect()) {
                String key="trade:"+environment+":1:catalog:"+next.generation()+":PRODUCT:"+query.identity();
                String correct=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(new RedisCatalogCache.Payload(query.identity(),value));
                for(String malformed:List.of(correct.replace("\"unitPrice\":100,",""),correct.replace("\"unitPrice\":100","\"unitPrice\":null"),correct.replace("\"unitPrice\":100","\"unitPrice\":1.5"),correct.replace("\"currency\":\"CNY\",",""),correct.replace("\"name\":\"商品\"","\"name\":null"),correct.replace("\"skuId\":\"1\"","\"skuId\":\"invalid\""))) {
                    assertNotEquals(correct,malformed);raw.sync().set(key,malformed,new io.lettuce.core.SetArgs().px(5000));assertFalse(cache.lookup(query).available(),"损坏缓存必须进入受限回源");
                }
                raw.sync().set(key,"x".repeat(300000),new io.lettuce.core.SetArgs().px(5000));assertFalse(cache.lookup(query).available());
            } finally {rawClient.shutdown();}

        }
    }
}
