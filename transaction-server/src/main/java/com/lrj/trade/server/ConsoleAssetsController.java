package com.lrj.trade.server;

import com.lrj.trade.catalog.CatalogService;
import com.lrj.trade.core.Actor;
import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.inventory.InventoryAlertService;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运营管理的商品展示资源和库存预警配置使用已验证的交易租户。 */
@RestController
@RequestMapping("/api/v1/trade/admin")
public class ConsoleAssetsController {
    private static final Set<String> STATES = Arrays.stream(InventoryAlertService.StockState.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());
    private final CatalogService catalog;
    private final InventoryAlertService warnings;

    /** 通过现有目录及库存领域写入，避免控制器穿透权限边界。 */
    public ConsoleAssetsController(CatalogService catalog, InventoryAlertService warnings) {
        this.catalog = catalog;
        this.warnings = warnings;
    }

    /** 修改并发受版本保护，空图片值清除配置，业务 SKU 及成交快照按原契约处理。 */
    @PatchMapping("/products/{productId}/image")
    public Version setImage(@AuthenticationPrincipal Jwt jwt, @PathVariable String productId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ProductImage input) {
        long id = AdminQuery.number(productId, false);
        return new Version(catalog.setImage(actor(jwt), key, id, input.version(), input.imageUrl()));
    }

    /** 预警统计覆盖全量筛选结果，列表支持按状态快捷进入。 */
    @AdminRead
    @GetMapping("/inventory-alerts")
    public InventoryAlertService.View alerts(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> params) {
        var request = AdminQueryParameters.numbered(params, input -> AdminQuery.parse(input,
                Map.of("warehouseId", Set.of(), "skuId", Set.of(), "state", STATES), false));
        return warnings.read(actor(jwt).tenantId(), request.query(), request.page());
    }

    /** 已存在的库存余额可独立维护补货阈值，清空用空值表达。 */
    @PatchMapping("/inventory-alerts/{warehouseId}/{skuId}")
    public Version setRule(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId, @PathVariable String skuId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReorderRule input) {
        return new Version(warnings.save(actor(jwt), key, AdminQuery.number(warehouseId, false),
                AdminQuery.number(skuId, false), input.reorderThreshold(), input.revision()));
    }

    /** 使用商品版本校验来源为调用者刚读到的资料。 */
    public record ProductImage(@NotNull @PositiveOrZero Long version, String imageUrl) {}
    /** 阈值版本独立于库存版本。 */
    public record ReorderRule(@NotNull @PositiveOrZero Long revision, @PositiveOrZero Long reorderThreshold) {}
    /** 修改回执返回已提交版本，便于安全重试时对比。 */
    public record Version(long version) {}

    private static Actor actor(Jwt jwt) {
        return new Actor(AdminQuery.number(jwt.getClaimAsString("tenant_id"), false), jwt.getIssuer().toString(), jwt.getSubject());
    }
}
