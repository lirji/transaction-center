package com.lrj.trade.server;

import com.lrj.trade.core.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Kafka发送只在事务外执行；幂等生产者不能替代跨进程eventId去重。 */
public final class KafkaEventPublisher implements EventPublisherPort,AutoCloseable {
    private final Producer<String,String> producer;
    private final Map<String,String> topics;
    /** 主题由部署配置和固定类型目录生成，业务消息不能指定任意主题。 */
    public KafkaEventPublisher(Producer<String,String> producer,String prefix) {
        this.producer=Objects.requireNonNull(producer);
        if(prefix==null) throw new IllegalArgumentException("主题前缀缺失");
        var mapping=new HashMap<String,String>();
        for(String type:OutboxEventCatalog.supportedTypes()) {
            String topic=prefix+type;
            if(topic.length()>249 || !topic.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("主题映射无效");
            mapping.put(type,topic);
        }
        topics=Map.copyOf(mapping);
    }
    /** 仅显式开启配置才创建客户端，本实例存在即表示已开启。 */
    @Override public boolean enabled() { return true; }
    /** send元数据等待最多5秒，后续ACK等待最多15秒，留出30秒租约的本地提交余量。 */
    @Override public Receipt publish(OutboxEventCatalog.Message message) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("数据库事务内禁止发送Kafka");
        Objects.requireNonNull(message);
        String topic=topics.get(message.eventType());
        if(topic==null) throw new IllegalArgumentException("事件类型不在固定主题映射内");
        var record=new ProducerRecord<String,String>(topic,message.partitionKey(),message.payload());
        record.headers().add("eventId",message.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent",TraceContext.parentForEvent(message.traceId()).getBytes(StandardCharsets.UTF_8));
        try {
            var ack=producer.send(record).get(15000,TimeUnit.MILLISECONDS);
            if(ack==null || !topic.equals(ack.topic()) || !ack.hasOffset()) throw new IllegalStateException("Kafka确认与发送主题不一致");
            return new Receipt(message.eventId(),ack.topic(),ack.partition(),ack.offset());
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Kafka发送等待被中断");
        } catch(ExecutionException | TimeoutException uncertain) {
            // 不向工作者暴露客户端异常正文，超时不表示Broker没有收到消息。
            throw new IllegalStateException("Kafka发送结果未确认");
        }
    }
    /** 固定容量、acks和有界超时，禁止以Kafka默认较长等待跨过数据库租约。 */
    public static Properties producerProperties(String bootstrap) {
        Checks.text(bootstrap,2048);
        var p=new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG,"all");p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,5);
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,5000L);p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,5000);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,10000);p.put(ProducerConfig.LINGER_MS_CONFIG,0);
        p.put(ProducerConfig.BUFFER_MEMORY_CONFIG,8L*1024*1024);p.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG,2*1024*1024);
        p.put(ProducerConfig.CLIENT_ID_CONFIG,"transaction-center-outbox");
        return p;
    }
    /** 容器停机有界关闭，未确认消息由持久任务恢复。 */
    @Override public void close() { producer.close(Duration.ofSeconds(5)); }
}
