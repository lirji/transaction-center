package com.lrj.trade.inventory;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.AuditWriter;
import com.lrj.trade.core.Checks;
import com.lrj.trade.core.Commands;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.core.query.AdminNumberedPage;
import com.lrj.trade.core.query.AdminNumbers;
import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.inventory.mapper.InventoryAlertMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 补货阈值独立于库存守恒和可售判断，只表达真实运营配置。 */
@Service
public class InventoryAlertService {
    private final InventoryAlertMapper mapper;
    private final Commands commands;
    private final AuditWriter audit;

    /** 规则更新和审计通过既有幂等命令事务提交。 */
    public InventoryAlertService(InventoryAlertMapper mapper, Commands commands, AuditWriter audit) {
        this.mapper = mapper;
        this.commands = commands;
        this.audit = audit;
    }

    /** 缺货永远以实际可用量为零判断，低库存只适用于已配置阈值的库存行。 */
    public enum StockState { OUT_OF_STOCK, LOW, UNCONFIGURED, NORMAL }

    /** 对外仅返回运营所需的库存字段与真实补货配置。 */
    public record Alert(String warehouseId, String skuId, long available, Long reorderThreshold, long revision,
                        StockState state) {}

    /** 统计值按当次所查的仓库/SKU 范围读取，未设置阈值单独计数。 */
    public record Totals(StockState state, long count) {}

    /** 状态统计与明细使用同一观察快照。 */
    public record View(AdminNumberedPage<Alert> rows, List<Totals> totals) {}

    /** 默认包括全部库存行，指定状态只筛选明细，统计仍保留上下文。 */
    @Transactional(readOnly = true, timeout = 3, isolation = Isolation.REPEATABLE_READ)
    public View read(long tenant, AdminQuery query, long page) {
        Instant observedAt = Instant.now();
        var totals = mapper.totals(tenant, query);
        String state = (String) query.filters().get("state");
        long count = totals.stream().filter(item -> state == null || item.state().name().equals(state))
                .mapToLong(Totals::count).sum();
        return new View(AdminNumberedPage.of(page, query.limit(), count, observedAt,
                offset -> mapper.page(tenant, query, offset)), totals);
    }

    /** 与现有余额绑定，并且单独使用阈值版本进行并发控制。 */
    public long save(Actor actor, String key, long warehouse, long sku, Long threshold, long expectedRevision) {
        Checks.positive(warehouse);
        Checks.positive(sku);
        if ((threshold != null && (threshold < 0 || threshold > AdminNumbers.MAX_SAFE_INTEGER))
                || expectedRevision < 0 || expectedRevision >= AdminNumbers.MAX_SAFE_INTEGER) throw AdminQuery.invalid();
        return Long.parseLong(commands.run(actor, "inventory.reorder-rule.save", key,
                Checks.hash(warehouse, sku, threshold, expectedRevision), () -> {
                    if (mapper.set(actor.tenantId(), warehouse, sku, threshold, expectedRevision) != 1) {
                        if (!mapper.exists(actor.tenantId(), warehouse, sku)) {
                            throw new TradeException("NOT_FOUND", "库存余额不存在");
                        }
                        throw new TradeException("VERSION_CONFLICT", "补货规则已被更新");
                    }
                    audit.append(actor, "inventory.reorder-rule.save", "INVENTORY_BALANCE", warehouse + ":" + sku,
                            "更新补货配置", expectedRevision, expectedRevision + 1);
                    return Long.toString(expectedRevision + 1);
                }));
    }
}
