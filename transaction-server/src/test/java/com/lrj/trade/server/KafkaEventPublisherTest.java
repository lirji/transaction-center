package com.lrj.trade.server;

import com.lrj.trade.core.*;
import java.time.Instant;
import java.util.concurrent.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 客户端合同单测不冒充真实Broker收取，实际网络另由KafkaPublisherIT验证。 */
class KafkaEventPublisherTest {
    private OutboxEventCatalog.Message message() {
        var e=MemberRegisteredEvent.create(1,2,Instant.parse("2026-09-08T00:00:00Z"));
        return OutboxEventCatalog.validate(1,e.eventId(),e.eventType(),e.aggregateId(),MemberEventCodec.encode(e));
    }
    @Test void clientPreservesPayloadKeyAndHeaderAndUsesFixedTopic() {
        var mock=new MockProducer<String,String>(true,null,new StringSerializer(),new StringSerializer());
        try(var publisher=new KafkaEventPublisher(mock,"test.")) {
            var m=message();var receipt=publisher.publish(m);var record=mock.history().getFirst();
            assertEquals("test.trade.member-registered.v1",receipt.topic());assertEquals(m.eventId(),receipt.eventId());
            assertEquals(m.payload(),record.value());assertEquals(m.partitionKey(),record.key());
            assertEquals(m.eventId(),new String(record.headers().lastHeader("eventId").value(),java.nio.charset.StandardCharsets.UTF_8));
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThrows(IllegalStateException.class,()->publisher.publish(m)); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertEquals(1,mock.history().size());
        }
        assertTrue(mock.closed());
    }
    @Test void persistedEventTraceWinsOverReplayRequestAndLegacyZeroStartsSafeRoot() {
        OutboxEventCatalog.Message original;
        try(var scope=TraceContext.open(TraceContextTest.PARENT)) {original=message();}
        var mock=new MockProducer<String,String>(true,null,new StringSerializer(),new StringSerializer());
        try(var publisher=new KafkaEventPublisher(mock,"test.");var replay=TraceContext.open(null)) {
            String replayTrace=TraceContext.current().traceId();publisher.publish(original);publisher.publish(original);
            for(var sent:mock.history()) {
                var propagated=TraceContext.parse(new String(sent.headers().lastHeader("traceparent").value(),java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(original.traceId().replace("-",""),propagated.traceId());assertNotEquals(replayTrace,propagated.traceId());assertEquals(original.payload(),sent.value());
            }
            String oldPayload=original.payload().replace(original.traceId(),"00000000-0000-0000-0000-000000000000");
            var legacy=OutboxEventCatalog.validate(1,original.eventId(),original.eventType(),"2",oldPayload);publisher.publish(legacy);
            assertNotNull(TraceContext.parse(new String(mock.history().getLast().headers().lastHeader("traceparent").value(),java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(oldPayload,mock.history().getLast().value());assertEquals(replayTrace,TraceContext.current().traceId());
        }
    }
    @Test void uncertainAckIsNotSuccessAndTimeoutBudgetFitsLease() throws Exception {
        @SuppressWarnings("unchecked") Producer<String,String> producer=mock(Producer.class);
        @SuppressWarnings("unchecked") Future<RecordMetadata> future=mock(Future.class);
        when(producer.send(any())).thenReturn(future);
        when(future.get(15000,TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException("sensitive network details"));
        try(var publisher=new KafkaEventPublisher(producer,"test.")) {
            var failure=assertThrows(IllegalStateException.class,()->publisher.publish(message()));
            assertNull(failure.getCause());assertFalse(failure.getMessage().contains("sensitive"));
        }
        var config=new ProducerConfig(KafkaEventPublisher.producerProperties("127.0.0.1:49092"));
        assertEquals("all",KafkaEventPublisher.producerProperties("127.0.0.1:49092").get(ProducerConfig.ACKS_CONFIG));
        assertEquals("-1",config.getString(ProducerConfig.ACKS_CONFIG)); // Kafka规范化all为-1。
        assertTrue(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        assertTrue(config.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)+15000<30000);
        assertEquals(10000,config.getInt(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));
    }
    @Test void defaultConfigCreatesNoKafkaClientAndEnabledMissingBootstrapFails() {
        new ApplicationContextRunner().withUserConfiguration(EventPublisherConfiguration.class).run(c->{
            assertFalse(c.getBean(EventPublisherPort.class).enabled());assertTrue(c.getBeansOfType(KafkaEventPublisher.class).isEmpty());
        });
        new ApplicationContextRunner().withUserConfiguration(EventPublisherConfiguration.class)
            .withPropertyValues("trade.outbox.enabled=true").run(c->assertNotNull(c.getStartupFailure()));
    }
}
