package com.lrj.trade.core;

import com.lrj.trade.core.mapper.OutboxDeliveryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 状态与ACK位置的持久边界；本类不执行网络，也不自行提交租约。 */
@Service
public class OutboxDeliveryService implements OutboxDeliveryPort {
    private final OutboxDeliveryMapper mapper;
    /** 使用共享事务管理器，确保发布完成与任务fencing同生共死。 */
    public OutboxDeliveryService(OutboxDeliveryMapper mapper) { this.mapper=mapper; }
    /** 当前读禁止用RR旧快照判断发送终态。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Row lock(long tenant,String eventId) {
        Checks.positive(tenant); Checks.text(eventId,36);
        var rows=mapper.lock(tenant,eventId); return rows.isEmpty()?null:rows.getFirst();
    }
    /** 必须由发布工作者在ACK后调用，并在事务末尾校验任务租约。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void sent(long tenant,String eventId,EventPublisherPort.Receipt receipt) {
        Checks.positive(tenant); Checks.text(eventId,36);
        if(receipt==null || !eventId.equals(receipt.eventId())) throw new IllegalArgumentException("ACK事件不匹配");
        if(mapper.sent(tenant,eventId,receipt.topic(),receipt.partition(),receipt.offset())!=1)
            throw new TradeException("OUTBOX_STATE_CONFLICT","发布状态已变化");
    }
    /** 失败登记只改变待发送事件，已确认记录不能倒退。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void failed(long tenant,String eventId,String code,boolean review) {
        Checks.positive(tenant); Checks.text(eventId,36);
        if(code==null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("发布错误必须分类脱敏");
        mapper.failed(tenant,eventId,code,review?"REVIEW":"PENDING");
    }
}
