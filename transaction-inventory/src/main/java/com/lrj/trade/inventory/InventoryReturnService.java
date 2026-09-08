package com.lrj.trade.inventory;
import com.lrj.trade.core.*;
import com.lrj.trade.inventory.mapper.InventoryMapper;
import com.lrj.trade.inventory.mapper.InventoryReturnMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 验收回库与原事务共同提交；不重新打开已确认预占，也不把回库计作新增总库存。 */
@Service
public class InventoryReturnService implements InventoryReturnPort {
    private final InventoryMapper inventory;
    private final InventoryReturnMapper mapper;
    private final Outbox outbox;
    private final InventoryEventWriter events;
    /** 专用持久化端口保留原预占预算及跨仓流水原子性。 */
    public InventoryReturnService(InventoryMapper inventory,InventoryReturnMapper mapper,Outbox outbox,InventoryEventWriter events) {
        this.inventory=inventory; this.mapper=mapper; this.outbox=outbox; this.events=events;
    }
    /** 验收不可售品仍需合法接收仓；不创建库存余额。 */
    public void requireWarehouse(long tenant,long warehouse) {
        if(inventory.countWarehouse(tenant,warehouse)!=1) throw new TradeException("NOT_FOUND","接收仓库不存在");
    }
    /** 必须加入验收事务；先锁原预占，再占永久操作键，双仓初始化与加锁使用同一顺序。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public String restock(Actor actor,String operation,String reservationKey,long sku,long originalQuantity,long warehouse,long quantity) {
        Checks.text(operation,48); Checks.text(reservationKey,128); Checks.positive(sku); Checks.positive(warehouse);
        Checks.positive(originalQuantity); Checks.positive(quantity);
        var rows=inventory.lockReservation(actor.tenantId(),reservationKey);
        if(rows.isEmpty()) throw conflict();
        var r=rows.getFirst();
        if(!r.status().equals("CONFIRMED") || r.sku()!=sku || r.quantity()!=originalQuantity) throw conflict();
        if(inventory.countWarehouse(actor.tenantId(),warehouse)!=1) throw new TradeException("NOT_FOUND","接收仓库不存在");
        String hash=Checks.hash(reservationKey,sku,originalQuantity,warehouse,quantity);
        String token=java.util.UUID.randomUUID().toString();
        mapper.insertOperation(actor.tenantId(),operation,Long.parseLong(r.id()),warehouse,quantity,hash,token);
        var op=mapper.lockOperation(actor.tenantId(),operation);
        if(!op.requestHash().equals(hash)) throw conflict();
        if(!op.claimToken().equals(token)) return operation;
        if(mapper.consumeBudget(actor.tenantId(),Long.parseLong(r.id()),quantity)!=1) throw conflict();
        // UPSERT本身也加锁，必须在接触任一余额前排序，避免双向跨仓退货死锁。
        for(long w:java.util.stream.LongStream.of(r.warehouse(),warehouse).distinct().sorted().toArray())
            inventory.insertBalanceIfAbsent(actor.tenantId(),w,sku);
        if(r.warehouse()==warehouse) {
            changed(mapper.moveBalance(actor.tenantId(),warehouse,sku,quantity,-quantity,0,quantity));
            ledger(actor,operation+":IN",warehouse,sku,quantity,-quantity,0);
        } else {
            changed(mapper.moveBalance(actor.tenantId(),r.warehouse(),sku,0,-quantity,-quantity,quantity));
            changed(mapper.moveBalance(actor.tenantId(),warehouse,sku,quantity,0,quantity,0));
            ledger(actor,operation+":OUT",r.warehouse(),sku,0,-quantity,-quantity);
            ledger(actor,operation+":IN",warehouse,sku,quantity,0,quantity);
        }
        outbox.append(actor.tenantId(),"inventory.returned.internal.v0",operation);
        return operation;
    }
    private void ledger(Actor actor,String ref,long warehouse,long sku,long available,long sold,long total) {
        inventory.insertLedger(actor.tenantId(),ref,warehouse,sku,"RETURN",available,0,sold,total,"验收可售回库",actor.key());
        events.append(actor.tenantId(),warehouse,sku,ref);
    }
    private void changed(int n) { if(n!=1) throw conflict(); }
    private TradeException conflict() { return new TradeException("RETURN_CONFLICT","原预占、永久操作或累计可售回库数量不匹配"); }
}
