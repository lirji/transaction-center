package com.lrj.trade.server;

import com.lrj.trade.core.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 显式运行的dev_infra集成测试，只写专用短保留主题，不删除共享主题或消费组。 */
class KafkaPublisherIT {
    @Test void devInfraBrokerConfirmsAndReturnsExactV1PayloadAtAckOffset() throws Exception {
        String bootstrap=System.getProperty("trade.test.kafka.bootstrap");
        assertNotNull(bootstrap,"必须显式提供dev_infra测试Broker地址");
        String topic="transaction-center-verification.trade.member-registered.v1";
        try(var admin=Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,10000,AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,5000))) {
            try { admin.createTopics(List.of(new NewTopic(topic,1,(short)1).configs(Map.of("retention.ms","3600000")))).all().get(15,TimeUnit.SECONDS); }
            catch(ExecutionException exists) { if(!(exists.getCause() instanceof TopicExistsException)) throw exists; }
        }
        var event=MemberRegisteredEvent.create(900000001,900000002,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        String payload=" \n"+MemberEventCodec.encode(event)+"\n ";
        var message=OutboxEventCatalog.validate(900000001,event.eventId(),event.eventType(),event.aggregateId(),payload);
        EventPublisherPort.Receipt ack;
        try(var publisher=new KafkaEventPublisher(new KafkaProducer<String,String>(KafkaEventPublisher.producerProperties(bootstrap)),"transaction-center-verification.")) {
            ack=publisher.publish(message);
        }
        var props=new Properties();props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,10000);
        // 直接定位本次ACK，不加入消费组或读取无关业务主题。
        try(var consumer=new KafkaConsumer<String,String>(props)) {
            var partition=new TopicPartition(ack.topic(),ack.partition());consumer.assign(List.of(partition));consumer.seek(partition,ack.offset());
            ConsumerRecord<String,String> found=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(found==null && System.nanoTime()<deadline) {
                for(var record:consumer.poll(Duration.ofMillis(500))) if(record.offset()==ack.offset()) { found=record;break; }
            }
            assertNotNull(found);assertEquals(payload,found.value());assertEquals(message.partitionKey(),found.key());
                assertEquals(event.traceId().replace("-",""),TraceContext.parse(new String(found.headers().lastHeader("traceparent").value(),java.nio.charset.StandardCharsets.UTF_8)).traceId());
            assertEquals(event.eventId(),MemberEventCodec.decode(found.value()).eventId());
            assertEquals(event.eventId(),new String(found.headers().lastHeader("eventId").value(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
