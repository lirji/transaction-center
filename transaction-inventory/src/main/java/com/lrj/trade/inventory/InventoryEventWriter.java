package com.lrj.trade.inventory;
import com.lrj.trade.core.*;
import com.lrj.trade.inventory.mapper.InventoryMapper;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
/** 在已更新余额的原事务中当前读，禁止使用旧RR快照拼接新流水版本。 */
@Component
public class InventoryEventWriter {
    private final InventoryMapper mapper;
    private final Outbox outbox;
    private final Clock clock;
    /** 调用者已持有余额锁；复用同一数据源，不新增跨仓锁序。 */
    public InventoryEventWriter(InventoryMapper mapper,Outbox outbox,Clock clock) { this.mapper=mapper; this.outbox=outbox; this.clock=clock; }
    /** 每条实际流水生成对应仓库的完整快照，重复业务提前返回时不调用。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(long tenant,long warehouse,long sku,String operation) {
        var rows=mapper.lockBalance(tenant,warehouse,sku);
        if(rows.size()!=1) throw new TradeException("STATE_CONFLICT","库存流水缺少对应余额");
        var b=rows.getFirst();
        outbox.inventoryChanged(InventoryChangedEvent.create(tenant,clock.instant().truncatedTo(ChronoUnit.MILLIS),
            new InventoryChangedEvent.Data(b.warehouseId(),b.skuId(),operation,b.available(),b.reserved(),b.sold(),b.stockTotal(),b.version())));
    }
}
