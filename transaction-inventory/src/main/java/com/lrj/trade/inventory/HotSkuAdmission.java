package com.lrj.trade.inventory;
import com.lrj.trade.core.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
/** 每实例热点预占预算只限制新增受理，正确性继续由主库库存条件更新保证。 */
@Component
public class HotSkuAdmission {
    private final java.util.concurrent.atomic.AtomicLong rejected=new java.util.concurrent.atomic.AtomicLong();
    private final int permits;private final Semaphore keys;private final ConcurrentHashMap<Key,Semaphore> slots=new ConcurrentHashMap<>();private final Object resource=new Object();
    /** 同时限制单键并发与跟踪键数，禁止用无限Map承接任意SKU输入。 */
    public HotSkuAdmission(@Value("${trade.inventory.hot-sku-permits:32}") int permits,@Value("${trade.inventory.hot-sku-max-keys:4096}") int maxKeys) {
        if(permits<1 || permits>256 || maxKeys<1 || maxKeys>65536) throw new IllegalArgumentException("热点SKU预算配置无效");this.permits=permits;this.keys=new Semaphore(maxKeys);
    }
    /** 同事务同键只占一次，槽位保持至外层提交或回滚，拒绝时不等待数据库锁。 */
    public void acquire(long tenant,long warehouse,long sku) {
        Checks.positive(tenant);Checks.positive(warehouse);Checks.positive(sku);
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("热点准入必须加入库存受理事务");
        Holder holder=(Holder)TransactionSynchronizationManager.getResource(resource);
        if(holder==null) {holder=new Holder();TransactionSynchronizationManager.bindResource(resource,holder);TransactionSynchronizationManager.registerSynchronization(holder);}
        Key key=new Key(tenant,warehouse,sku);if(holder.held.containsKey(key)) return;
        Semaphore acquired=slots.compute(key,(ignored,current)->{
            if(current==null) {if(!keys.tryAcquire()) throw busy();current=new Semaphore(permits);}
            if(!current.tryAcquire()) throw busy();return current;
        });holder.held.put(key,acquired);
    }
    /** 暴露有界基数供指标和回滚清理验证，不包含原始SKU标签。 */
    public int trackedKeys() {return slots.size();}
    /** 累计拒绝计数不带SKU或租户高基数标签。 */
    public long rejectedCount() {return rejected.get();}
    private TradeException busy() {rejected.incrementAndGet();return new TradeException("HOT_SKU_BUSY","热点SKU受理繁忙，请保留原幂等键稍后重试");}
    /** 事务身份由同步生命周期隔离，REQUIRES_NEW不能复用外层许可。 */
    private final class Holder implements TransactionSynchronization {
        private final Map<Key,Semaphore> held=new HashMap<>();
        /** Spring只自动挂起内置资源，自定义事务复用表须主动解绑。 */
        @Override public void suspend() {if(TransactionSynchronizationManager.getResource(resource)==this) TransactionSynchronizationManager.unbindResource(resource);}
        /** 内层结束后恢复外层许可集合，内层清理不能提前释放它。 */
        @Override public void resume() {TransactionSynchronizationManager.bindResource(resource,this);}
        /** 真实提交或回滚才回收槽位；计算与移除在同一键原子完成。 */
        @Override public void afterCompletion(int status) {
            if(TransactionSynchronizationManager.getResource(resource)==this) TransactionSynchronizationManager.unbindResource(resource);
            held.forEach((key,permit)->slots.compute(key,(ignored,current)->{
                if(current!=permit) throw new IllegalStateException("热点槽位引用不一致");current.release();
                if(current.availablePermits()==permits) {keys.release();return null;}return current;
            }));held.clear();
        }
    }
    /** 仓库间库存独立，不能把同SKU的其他仓库无故串行化。 */
    private record Key(long tenant,long warehouse,long sku) {}
}
