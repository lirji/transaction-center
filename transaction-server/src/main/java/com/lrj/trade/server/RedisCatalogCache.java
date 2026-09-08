package com.lrj.trade.server;
import com.lrj.trade.catalog.CatalogCachePort;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
/** 共享Redis仅缓存商品展示；故障返回显式降级，不修改任何资金判断。 */
@Component
public class RedisCatalogCache implements CatalogCachePort,AutoCloseable {
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final boolean enabled;private final String environment;private final RedisClient client;private final Semaphore io;private final ThreadPoolExecutor invalidations;private final ReentrantLock connecting=new ReentrantLock();private volatile StatefulRedisConnection<String,String> connection;private volatile long reconnectAfter;
    private final AtomicLong failures=new AtomicLong(),invalidated=new AtomicLong(),rejected=new AtomicLong();
    /** 关闭时不创建Redis连接；启用必须使用项目环境前缀及专用ACL凭据。 */
    public RedisCatalogCache(@org.springframework.beans.factory.annotation.Value("${trade.catalog.cache-enabled:false}") boolean enabled,@org.springframework.beans.factory.annotation.Value("${trade.catalog.cache-environment:dev}") String environment,@org.springframework.beans.factory.annotation.Value("${trade.catalog.redis-host:127.0.0.1}") String host,@org.springframework.beans.factory.annotation.Value("${trade.catalog.redis-port:46379}") int port,@org.springframework.beans.factory.annotation.Value("${trade.catalog.redis-user:}") String user,@org.springframework.beans.factory.annotation.Value("${trade.catalog.redis-password:}") String password,@org.springframework.beans.factory.annotation.Value("${trade.catalog.redis-io-limit:32}") int ioLimit,@org.springframework.beans.factory.annotation.Value("${trade.catalog.redis-timeout-ms:50}") long timeout) {
        if(!environment.matches("[a-z][a-z0-9-]{0,31}") || port<1 || port>65535 || ioLimit<1 || ioLimit>256 || timeout<10 || timeout>200 || enabled && (user.isBlank() || user.equals("default") || password.isBlank() || host.isBlank())) throw new IllegalArgumentException("商品Redis缓存配置无效");
        this.enabled=enabled;this.environment=environment;io=new Semaphore(ioLimit);
        if(enabled) {
            var uri=RedisURI.Builder.redis(host,port).withAuthentication(user,password.toCharArray()).withTimeout(Duration.ofMillis(timeout)).build();client=RedisClient.create(uri);
            client.setOptions(ClientOptions.builder().autoReconnect(false).socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build()).timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(timeout))).build());
            invalidations=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),task->{var thread=new Thread(task,"catalog-cache-invalidate");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        } else {client=null;invalidations=null;}
    }
    /** 代次初始化原子争抢后读取赢家，同代次键才允许命中。 */
    @Override public Probe lookup(Query query) {
        noTransaction();if(!enabled || !permit()) return unavailable();
        try {
            var commands=commands();String key=generationKey(query.tenant());String generation=commands.getrange(key,0,36);
            if(generation==null || generation.isEmpty()) {commands.set(key,UUID.randomUUID().toString(),new SetArgs().nx().px(3_600_000));generation=commands.getrange(key,0,36);}
            canonical(generation);String raw=commands.getrange(valueKey(query,generation),0,262144);if(raw==null || raw.isEmpty()) return new Probe(generation,null,true);
            if(raw.length()>262144 || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144) throw new IllegalArgumentException("缓存载荷超限");
            var payload=JSON.readValue(raw,Payload.class);if(!query.identity().equals(payload.identity())) throw new IllegalArgumentException("缓存查询绑定不符");query.validate(payload.value());return new Probe(generation,payload.value(),true);
        } catch(RuntimeException error) {failures.incrementAndGet();return unavailable();} finally {io.release();}
    }
    /** 旧回源只写旧代次；负缓存短TTL，正缓存带抖动且大小有界。 */
    @Override public void store(Query query,String generation,Value value) {
        noTransaction();if(!enabled || !permit()) return;
        try {canonical(generation);query.validate(value);String payload=JSON.writeValueAsString(new Payload(query.identity(),value));if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144) return;
            long ttl=value.empty()?2_000+ThreadLocalRandom.current().nextLong(500):30_000+ThreadLocalRandom.current().nextLong(5_000);commands().set(valueKey(query,generation),payload,new SetArgs().px(ttl));
        } catch(RuntimeException error) {failures.incrementAndGet();} finally {io.release();}
    }
    /** 提交后仅投递有界异步任务，拒绝不能把已提交发布响应改成失败。 */
    @Override public void invalidateAfterCommit(long tenant) {
        if(!enabled) return;if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("目录失效必须注册在发布事务内");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 网络发生在不继承交易连接的执行器线程，失败由数据TTL兜底。 */
            @Override public void afterCommit() {try {invalidations.execute(()->rotate(tenant));}catch(RejectedExecutionException full) {rejected.incrementAndGet();}}
        });
    }
    private void rotate(long tenant) {noTransaction();if(!permit()) return;try {commands().set(generationKey(tenant),UUID.randomUUID().toString(),new SetArgs().px(3_600_000));invalidated.incrementAndGet();}catch(RuntimeException error){failures.incrementAndGet();}finally{io.release();}}
    private RedisCommands<String,String> commands() {
        var found=connection;if(found!=null && found.isOpen()) return found.sync();
        if(System.nanoTime()<reconnectAfter || !connecting.tryLock()) throw new IllegalStateException("Redis连接暂不可用");
        try {found=connection;if(found!=null && found.isOpen()) return found.sync();// 断开的连接仍可能注册在客户端；先有界清理，不能覆盖引用后不断积累endpoint。
            if(found!=null) {try {found.closeAsync().get(100,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Redis连接清理中断",interrupted);}catch(ExecutionException | TimeoutException failed){throw new IllegalStateException("Redis旧连接清理未完成",failed);}}
            connection=client.connect();return connection.sync();}
        catch(RuntimeException failure){reconnectAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);throw failure;}finally{connecting.unlock();}
    }
    private boolean permit() {if(io.tryAcquire()) return true;rejected.incrementAndGet();return false;}
    private Probe unavailable() {return new Probe(null,null,false);}
    private String generationKey(long tenant) {return "trade:"+environment+":"+tenant+":catalog:generation";}
    private String valueKey(Query query,String generation) {return "trade:"+environment+":"+query.tenant()+":catalog:"+generation+":"+query.kind()+":"+query.identity();}
    private void canonical(String generation) {if(generation==null || !UUID.fromString(generation).toString().equals(generation)) throw new IllegalArgumentException("目录代次无效");}
    private void noTransaction() {if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Redis展示缓存不能在数据库事务内调用");}
    /** 低基数故障计数，不记录Redis地址凭据或业务键。 */
    public Statistics statistics() {return new Statistics(failures.get(),invalidated.get(),rejected.get());}
    /** 关闭只影响本实例连接和执行器，不删除共享Redis数据。 */
    @jakarta.annotation.PreDestroy
    @Override public void close() {if(invalidations!=null) invalidations.shutdownNow();var found=connection;if(found!=null) found.close();if(client!=null) client.shutdown();}
    /** 值内再次绑定规范查询，防止租户键或分页键串值。 */
    public record Payload(String identity,Value value) {}
    /** 指标计数不含高基数标签。 */
    public record Statistics(long failures,long invalidations,long rejected) {}
}
