package com.lrj.trade.server;
import com.lrj.trade.catalog.CatalogCachePort;
import com.lrj.trade.core.TradeException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
class RedisCatalogCacheTest {
    @Test void disabledCacheNeedsNoCredentialsOrNetwork() {
        try(var cache=new RedisCatalogCache(false,"test","127.0.0.1",1,"","",1,50)) {
            assertFalse(cache.lookup(new CatalogCachePort.Query(1,CatalogCachePort.Kind.PRODUCT,1,0,1)).available());assertEquals(0,cache.statistics().failures());
        }
    }
    @Test void enablingRequiresDedicatedUserAndSafeConfiguration() {
        assertThrows(IllegalArgumentException.class,()->new RedisCatalogCache(true,"test","127.0.0.1",1,"default","password",1,50));
        assertThrows(IllegalArgumentException.class,()->new RedisCatalogCache(true,"bad:namespace","127.0.0.1",1,"trade","password",1,50));
    }
    @Test void networkGuardRejectsOuterTransactionAndBudgetResponseIsRetryable() {
        try(var cache=new RedisCatalogCache(false,"test","127.0.0.1",1,"","",1,50)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {assertThrows(IllegalStateException.class,()->cache.lookup(new CatalogCachePort.Query(1,CatalogCachePort.Kind.PRODUCT,1,0,1)));}
            finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
        }
        for(String code:new String[]{"HOT_SKU_BUSY","CATALOG_QUERY_BUSY"}) {var response=new Errors().business(new TradeException(code,"busy"));assertEquals(429,response.getStatusCode().value());assertEquals("1",response.getHeaders().getFirst("Retry-After"));assertTrue(response.getBody().retryable());}
    }
}
