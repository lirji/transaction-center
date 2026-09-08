package com.lrj.trade.server;

import com.lrj.trade.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
/** 未配置发布时不创建网络客户端，API只持久化事件及任务。 */
@Configuration(proxyBeanMethods=false)
public class EventPublisherConfiguration {
    /** dev_infra网络客户端只在显式启用时创建；缺bootstrap直接启动失败。 */
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="trade.outbox.enabled",havingValue="true")
    public KafkaEventPublisher kafkaEventPublisher(
        @org.springframework.beans.factory.annotation.Value("${trade.outbox.bootstrap-servers:}") String bootstrap,
        @org.springframework.beans.factory.annotation.Value("${trade.outbox.topic-prefix:}") String prefix) {
        // 先验证主题，再创建持有网络线程的Producer，配置错误不能泄露后台线程。
        var properties=KafkaEventPublisher.producerProperties(bootstrap);
        if(prefix==null || prefix.length()>180 || !prefix.matches("[A-Za-z0-9._-]*")) throw new IllegalArgumentException("主题前缀无效");
        return new KafkaEventPublisher(new org.apache.kafka.clients.producer.KafkaProducer<String,String>(properties),prefix);
    }

    /** 默认关闭实现确保工作者不领取任务，不能伪造成功ACK。 */
    @Bean
    @ConditionalOnProperty(name="trade.outbox.enabled",havingValue="false",matchIfMissing=true)
    public EventPublisherPort disabledEventPublisher() {
        return new EventPublisherPort() {
            /** 关闭实例不允许领取任务。 */
            @Override public boolean enabled() { return false; }
            /** 配置关闭不能产生伪造确认。 */
            @Override public Receipt publish(OutboxEventCatalog.Message message) { throw new IllegalStateException("事件发布尚未启用"); }
        };
    }
}
