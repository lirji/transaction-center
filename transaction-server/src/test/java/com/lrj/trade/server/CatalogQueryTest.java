package com.lrj.trade.server;
import com.lrj.trade.catalog.*;
import com.lrj.trade.catalog.model.Product;
import com.lrj.trade.core.TradeException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CatalogQueryTest {
    private CatalogCachePort.Value value(long id,String name,long revision) {return new CatalogCachePort.Value(List.of(new Product(Long.toString(id),Long.toString(id),name,"PUBLISHED",revision,100,"CNY",revision)),List.of());}
    private CatalogQueryService service(CatalogCachePort cache,CatalogQueryLoader loader,int concurrency,int rate) {return new CatalogQueryService(cache,loader,16,concurrency,rate,1000);}
    @Test void sameGenerationSingleFlightHasOneLeaderAndCleansKeys() throws Exception {
        var cache=mock(CatalogCachePort.class);var loader=mock(CatalogQueryLoader.class);var stored=new AtomicReference<CatalogCachePort.Value>();String generation=UUID.randomUUID().toString();
        when(cache.lookup(any())).thenAnswer(c->new CatalogCachePort.Probe(generation,stored.get(),true));doAnswer(c->{stored.set(c.getArgument(2));return null;}).when(cache).store(any(),anyString(),any());
        var gate=new CountDownLatch(1);var entered=new CountDownLatch(1);when(loader.load(any())).thenAnswer(c->{entered.countDown();if(!gate.await(3,TimeUnit.SECONDS)) throw new AssertionError("leader未释放");return value(1,"商品",1);});
        var service=service(cache,loader,2,64);
        try(var pool=Executors.newFixedThreadPool(20)) {
            List<Future<Product>> futures=new ArrayList<>();for(int i=0;i<20;i++) futures.add(pool.submit(()->service.product(1,1)));
            try {assertTrue(entered.await(2,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(service.statistics().joined()<10 && System.nanoTime()<deadline) Thread.sleep(1);assertTrue(service.statistics().joined()>=10);}finally{gate.countDown();}
            for(var future:futures) assertEquals("商品",future.get(2,TimeUnit.SECONDS).name());
        } finally {gate.countDown();}
        verify(loader,times(1)).load(any());assertEquals(0,service.statistics().pendingKeys());
    }
    @Test void newGenerationCannotJoinOldInFlightRead() throws Exception {
        var cache=mock(CatalogCachePort.class);var loader=mock(CatalogQueryLoader.class);var generation=new AtomicReference<>(UUID.randomUUID().toString());when(cache.lookup(any())).thenAnswer(c->new CatalogCachePort.Probe(generation.get(),null,true));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();when(loader.load(any())).thenAnswer(c->{if(calls.incrementAndGet()==1){entered.countDown();if(!release.await(3,TimeUnit.SECONDS)) throw new AssertionError();return value(1,"旧版本",1);}return value(1,"新版本",2);});var service=service(cache,loader,2,64);
        try(var pool=Executors.newFixedThreadPool(2)) {var old=pool.submit(()->service.product(1,1));try {assertTrue(entered.await(2,TimeUnit.SECONDS));generation.set(UUID.randomUUID().toString());assertEquals("新版本",pool.submit(()->service.product(1,1)).get(1,TimeUnit.SECONDS).name());}finally{release.countDown();}assertEquals("旧版本",old.get(2,TimeUnit.SECONDS).name());}finally{release.countDown();}
        verify(loader,times(2)).load(any());assertEquals(0,service.statistics().pendingKeys());
    }
    @Test void unavailableCacheCannotExceedDatabaseConcurrency() throws Exception {
        var cache=mock(CatalogCachePort.class);var loader=mock(CatalogQueryLoader.class);when(cache.lookup(any())).thenReturn(new CatalogCachePort.Probe(null,null,false));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(loader.load(any())).thenAnswer(c->{entered.countDown();if(!release.await(3,TimeUnit.SECONDS)) throw new AssertionError();return value(1,"商品",1);});var service=service(cache,loader,1,64);
        try(var pool=Executors.newSingleThreadExecutor()) {var first=pool.submit(()->service.product(1,1));try {assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals("CATALOG_QUERY_BUSY",assertThrows(TradeException.class,()->service.product(1,2)).code());verify(loader,times(1)).load(any());}finally{release.countDown();}assertNotNull(first.get(2,TimeUnit.SECONDS));}finally{release.countDown();}assertEquals(0,service.statistics().pendingKeys());
    }
    @Test void databaseRateBudgetRejectsAnotherColdKey() {
        var cache=mock(CatalogCachePort.class);var loader=mock(CatalogQueryLoader.class);when(cache.lookup(any())).thenReturn(new CatalogCachePort.Probe(null,null,false));when(loader.load(any())).thenReturn(value(1,"商品",1));var service=service(cache,loader,1,1);assertNotNull(service.product(1,1));assertEquals("CATALOG_QUERY_BUSY",assertThrows(TradeException.class,()->service.product(1,2)).code());verify(loader,times(1)).load(any());
    }
    @Test void malformedCacheAndFailedStoreStillUseBoundedAuthoritativeRead() {
        var cache=mock(CatalogCachePort.class);var loader=mock(CatalogQueryLoader.class);when(cache.lookup(any())).thenReturn(new CatalogCachePort.Probe("g",value(2,"串值",1),true));when(loader.load(any())).thenReturn(value(1,"正确",1));var service=service(cache,loader,1,64);assertEquals("正确",service.product(1,1).name());
        when(cache.lookup(any())).thenReturn(new CatalogCachePort.Probe("g",null,true));doThrow(new IllegalStateException("cache down")).when(cache).store(any(),anyString(),any());assertEquals("正确",service.product(1,1).name());
    }
    @Test void outerTransactionIsRejectedBeforeCacheOrDatabase() {
        var cache=mock(CatalogCachePort.class);var loader=mock(CatalogQueryLoader.class);var service=service(cache,loader,1,64);TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->service.product(1,1));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}verifyNoInteractions(cache,loader);
    }
}
