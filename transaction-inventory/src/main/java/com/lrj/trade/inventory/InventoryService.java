package com.lrj.trade.inventory;

import com.lrj.trade.catalog.CatalogPort;
import com.lrj.trade.core.*;
import java.util.List;
import java.util.UUID;
import com.lrj.trade.inventory.mapper.InventoryMapper;
import com.lrj.trade.inventory.model.*;
import org.springframework.stereotype.Service;

/** 库存以数据库条件更新和流水保证守恒，不依赖Redis锁的存活时间。 */
@Service
public class InventoryService implements InventoryTradingPort {
    private final InventoryMapper mapper;
    private final Commands commands;
    private final HotSkuAdmission admission;
    private final CatalogPort catalog;
    private final Outbox outbox;
    private final InventoryEventWriter events;
    /** 通过商品公开端口校验SKU，不跨领域访问商品数据表。 */
    public InventoryService(InventoryMapper mapper,Commands commands,CatalogPort catalog,Outbox outbox,InventoryEventWriter events,HotSkuAdmission admission) {
        this.mapper=mapper; this.commands=commands; this.catalog=catalog; this.outbox=outbox; this.events=events; this.admission=admission;
    }
    /** 仓库创建和幂等记录同事务，业务编号重复交由唯一约束拒绝。 */
    public String warehouse(Actor actor,String key,String number,String name) {
        Checks.text(number,64); Checks.text(name,200);
        return commands.run(actor,"warehouse.create",key,Checks.hash(number,name),()->{
            mapper.insertWarehouse(actor.tenantId(),number,name);
            return mapper.findWarehouseId(actor.tenantId(),number);
        });
    }
    /** 盘点只修改可售量与总量；不能通过盘点释放仍有支付风险的订单预占。 */
    public String adjust(Actor actor,String key,long warehouse,long sku,long delta,String reason) {
        Checks.positive(warehouse); Checks.positive(sku); Checks.text(reason,255);
        if(delta==0 || delta>1_000_000_000L || delta< -1_000_000_000L)
            throw new TradeException("INVALID_ARGUMENT","调整数量超出单次限制");
        return commands.run(actor,"inventory.adjust",key,Checks.hash(warehouse,sku,delta,reason),()->{
            catalog.requireSku(actor.tenantId(),sku,false);
            requireWarehouse(actor.tenantId(),warehouse);
            mapper.insertBalanceIfAbsent(actor.tenantId(),warehouse,sku);
            int changed=mapper.adjustBalance(delta,delta,actor.tenantId(),warehouse,sku,Math.max(0,-delta));
            if(changed!=1) throw new TradeException("INSUFFICIENT_STOCK","盘点不能使可售库存为负");
            return ledger(actor,warehouse,sku,"ADJUST",delta,0,0,delta,reason);
        });
    }
    /** 供未来订单本地事务调用；不暴露公共HTTP接口，稳定键必须来自订单明细。 */
    public String reserve(Actor actor,String reservationKey,long warehouse,long sku,long quantity) {
        Checks.positive(warehouse); Checks.positive(sku); Checks.positive(quantity);
        if(quantity>1_000_000_000L) throw new TradeException("INVALID_ARGUMENT","预占数量超限");
        return commands.run(actor,"inventory.reserve",reservationKey,Checks.hash(warehouse,sku,quantity),()->{
            admission.acquire(actor.tenantId(),warehouse,sku);
            catalog.requireSku(actor.tenantId(),sku,true);
            if(mapper.reserveBalance(quantity,quantity,actor.tenantId(),warehouse,sku,quantity)!=1)
                throw new TradeException("INSUFFICIENT_STOCK","可售库存不足");
            mapper.insertReservation(actor.tenantId(),reservationKey,warehouse,sku,quantity);
            ledger(actor,warehouse,sku,"RESERVE",-quantity,quantity,0,0,reservationKey);
            return mapper.findReservationId(actor.tenantId(),reservationKey);
        });
    }
    /** 调用方必须已持久确认支付事实；预占只能从RESERVED转到CONFIRMED一次。 */
    public String confirm(Actor actor,String key,String reservationKey) { return transition(actor,key,reservationKey,true); }
    /** 调用方必须取得可靠关单终态；本服务不依据TTL主动释放库存。 */
    public String release(Actor actor,String key,String reservationKey) { return transition(actor,key,reservationKey,false); }

    private String transition(Actor actor,String key,String reservationKey,boolean confirm) {
        Checks.text(reservationKey,128);
        String target=confirm?"CONFIRMED":"RELEASED";
        return commands.run(actor,"inventory."+target,key,Checks.hash(reservationKey),()->{
            var rows=mapper.lockReservation(actor.tenantId(),reservationKey);
            if(rows.isEmpty()) throw new TradeException("NOT_FOUND","库存预占不存在");
            var r=rows.getFirst();
            if(target.equals(r.status())) return r.id();
            // 相反终态不可互转，否则同一份库存可能既售出又重新可售。
            if(!"RESERVED".equals(r.status())) throw new TradeException("STATE_CONFLICT","库存预占已进入相反终态");
            long available=confirm?0:r.quantity();
            long sold=confirm?r.quantity():0;
            if(mapper.settleReservedBalance(available,r.quantity(),sold,actor.tenantId(),r.warehouse(),r.sku(),r.quantity())!=1)
                throw new IllegalStateException("库存预占余额与事实不一致");
            mapper.updateReservationStatus(target,actor.tenantId(),r.id());
            ledger(actor,r.warehouse(),r.sku(),confirm?"CONFIRM":"RELEASE",available,-r.quantity(),sold,0,reservationKey);
            return r.id();
        });
    }
    /** 管理查询采用限定数量游标，不允许跨租户查看库存。 */
    public List<Balance> balances(long tenant,long after,int limit) {
        Checks.positive(tenant);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","分页参数无效");
        return mapper.listBalances(tenant,after,limit);
    }
    /** 仓库按内部主键有界分页，使前端不必硬编码仓库编号。 */
    public List<Warehouse> warehouses(long tenant,long after,int limit) {
        Checks.positive(tenant);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","分页参数无效");
        return mapper.listWarehouses(tenant,after,limit);
    }
    /** 流水查询必须指定SKU和仓库以命中索引，禁止无界扫描所有库存操作。 */
    public List<LedgerEntry> ledgers(long tenant,long warehouse,long sku,long after,int limit) {
        Checks.positive(tenant); Checks.positive(warehouse); Checks.positive(sku);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","分页参数无效");
        return mapper.listLedgerEntries(tenant,warehouse,sku,after,limit);
    }
    private void requireWarehouse(long tenant,long warehouse) {
        if(mapper.countWarehouse(tenant,warehouse)!=1)
            throw new TradeException("NOT_FOUND","仓库不存在");
    }
    private String ledger(Actor actor,long warehouse,long sku,String type,long available,long reserved,long sold,long total,String reason) {
        String ref=UUID.randomUUID().toString();
        mapper.insertLedger(actor.tenantId(),ref,warehouse,sku,type,available,reserved,sold,total,reason,actor.key());
        outbox.append(actor.tenantId(),"inventory.changed.internal.v0",ref);
        events.append(actor.tenantId(),warehouse,sku,ref);
        return ref;
    }




}
