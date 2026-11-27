package com.lrj.trade.server;

import com.lrj.trade.catalog.CatalogService;
import com.lrj.trade.core.Actor;
import com.lrj.trade.core.Checks;
import com.lrj.trade.core.Commands;
import com.lrj.trade.core.query.AdminQuery;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 批量目录工作沿用原命令，每一批在本地事务内全部成功或统一回滚。 */
@Service
public class CatalogBulkService {
    public static final int MAX_ROWS = 100;
    private static final String IMPORT = "product.bulk.import";
    private static final String CHANGE = "product.bulk.state";
    private static final String COMPLETED = "COMPLETED";
    private final CatalogService catalog;
    private final Commands commands;
    private final JsonMapper json = JsonMapper.builder().build();

    /** 导入和变更状态仍走目录服务，由既有领域逻辑检查版本、发布条件和事件。 */
    public CatalogBulkService(CatalogService catalog, Commands commands) {
        this.catalog = catalog;
        this.commands = commands;
    }

    /** 批量导入商品草稿。相同幂等键和原清单可读取同一组已创建的商品。 */
    public Result importDrafts(Actor actor, String key, List<Draft> rows) {
        var numbers = new HashSet<String>();
        var skuNumbers = new HashSet<String>();
        for (var row : rows) {
            if (!numbers.add(row.productNo()) || !skuNumbers.add(row.skuNo())) throw AdminQuery.invalid();
        }
        commands.run(actor, IMPORT, key, Checks.hash(json.writeValueAsString(rows)), () -> {
            rows.stream().sorted(Comparator.comparing(Draft::productNo)).forEach(row ->
                    catalog.create(actor, rowKey(IMPORT, key, row.productNo()), row.productNo(), row.name(),
                            row.skuNo(), row.unitPrice(), row.currency()));
            return COMPLETED;
        });
        return new Result(rows.stream().map(row -> new Item(row.productNo(), COMPLETED)).toList());
    }

    /** 已勾选商品用原版本提交，事务按商品主键顺序加锁。 */
    public Result changeState(Actor actor, String key, Action action, List<Selection> rows) {
        var ids = new HashSet<String>();
        for (var row : rows) {
            AdminQuery.number(row.productId(), false);
            if (!ids.add(row.productId())) throw AdminQuery.invalid();
        }
        commands.run(actor, CHANGE, key, Checks.hash(action.name(), json.writeValueAsString(rows)), () -> {
            rows.stream().sorted(Comparator.comparingLong(row -> AdminQuery.number(row.productId(), false))).forEach(row -> {
                long id = AdminQuery.number(row.productId(), false);
                String itemKey = rowKey(CHANGE, key, row.productId());
                if (action == Action.PUBLISH) catalog.publish(actor, itemKey, id, row.version());
                else catalog.unpublish(actor, itemKey, id, row.version());
            });
            return COMPLETED;
        });
        return new Result(rows.stream().map(row -> new Item(row.productId(), COMPLETED)).toList());
    }

    private static String rowKey(String type, String key, String identity) { return Checks.hash(type, key, identity); }

    /** 导入源与单个创建草稿共用字段和领域规则。 */
    public record Draft(String productNo, String name, String skuNo, long unitPrice, String currency) {}
    /** 借助稳定版本判断是否还能进行原操作。 */
    public record Selection(String productId, long version) {}
    /** 支持的批量动作专用于商品生命周期。 */
    public enum Action { PUBLISH, OFFLINE }
    /** 按原请求顺序回显每一行结果，便于核对。 */
    public record Result(List<Item> items) {}
    /** 商品业务编号或商品 ID 与结果一一对应。 */
    public record Item(String reference, String state) {}
}
