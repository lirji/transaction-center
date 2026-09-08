package com.lrj.trade.core;

import java.util.UUID;
import com.lrj.trade.core.mapper.OutboxMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 持久化事务事件；完整V1与历史内部引用共存，待发送不代表已投递。 */
@Component
public class Outbox {
    private final OutboxMapper mapper;
    private final JobSchedulerPort jobs;
    /** 注入共享事务数据源，保证消息与业务事实同生共死。 */
    public Outbox(OutboxMapper mapper,JobSchedulerPort jobs) { this.mapper=mapper; this.jobs=jobs; }
    /** 完整注册载荷必须与会员创建共事务；序列化失败同样回滚，不在发布时重建。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberRegistered(long tenant,long member,java.time.Instant registeredAt) {
        var event=MemberRegisteredEvent.create(tenant,member,registeredAt);
        insertV1(Long.parseLong(event.tenantId()),event.eventId(),event.eventType(),event.aggregateId(),MemberEventCodec.encode(event));
    }
    /** 首次正常收款事务内追加；索引与完整载荷来自同一已验证事件。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void orderPaid(OrderPaidEvent event) {
        java.util.Objects.requireNonNull(event);
        insertV1(Long.parseLong(event.tenantId()),event.eventId(),event.eventType(),event.aggregateId(),OrderPaidEventCodec.encode(event));
    }
    /** 与订单状态及库存/履约事实共事务，失败不得留下部分完成结果。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void orderCompleted(OrderCompletedEvent event) {
        java.util.Objects.requireNonNull(event);
        insertV1(Long.parseLong(event.tenantId()),event.eventId(),event.eventType(),event.aggregateId(),OrderCompletedEventCodec.encode(event));
    }
    /** 与订单状态及库存/履约事实共事务，失败不得留下部分完成结果。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void orderCancelled(OrderCancelledEvent event) {
        java.util.Objects.requireNonNull(event);
        insertV1(Long.parseLong(event.tenantId()),event.eventId(),event.eventType(),event.aggregateId(),OrderCancelledEventCodec.encode(event));
    }
    /** 正常退款金额/订单版本与事件共事务；两种通知共享同一已冻结快照。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundEvidence(RefundEvidenceEvent event) {
        java.util.Objects.requireNonNull(event);
        insertV1(Long.parseLong(event.tenantId()),event.eventId(),event.eventType(),event.aggregateId(),RefundEvidenceEventCodec.encode(event));
    }
    /** 库存快照与确定性流水同事务，失败不能只保留余额变动。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void inventoryChanged(InventoryChangedEvent event) {
        java.util.Objects.requireNonNull(event);
        insertV1(Long.parseLong(event.tenantId()),event.eventId(),event.eventType(),event.aggregateId(),InventoryChangedEventCodec.encode(event));
    }
    // 入队失败必须回滚领域事实和事件，关闭发布器只停止领取，不停止持久入队。
    private void insertV1(long tenant,String eventId,String type,String aggregate,String payload) {
        mapper.insertV1Event(tenant,eventId,type,aggregate,payload);
        jobs.enqueue(tenant,JobType.OUTBOX_PUBLISH,eventId);
    }
    /** 最小内部事件只携带引用，后续完整V1合同启用前不得投递给外部消费者。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(long tenant, String type, String aggregate) {
        mapper.insertEvent(tenant,UUID.randomUUID().toString(),type,aggregate,aggregate);
    }
}
