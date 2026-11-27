package com.lrj.trade.server;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.order.AdminOrderQueryService;
import com.lrj.trade.order.model.AdminOrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 只对已有运营写权限和查询权限提供批量工作及安全字段导出。 */
@RestController
@RequestMapping("/api/v1/trade/admin")
public class ConsoleFilesController {
    private static final int MAX_EXPORT_ROWS = 50_000;
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private final AdminOrderQueryService orders;
    private final CatalogBulkService bulk;

    /** 通过已有订单快照和商品命令执行。 */
    public ConsoleFilesController(AdminOrderQueryService orders, CatalogBulkService bulk) {
        this.orders = orders;
        this.bulk = bulk;
    }

    /** 同一个请求的所有订单来自一个只读快照，超预算时返回失败且不响应半份文件。 */
    @AdminRead
    @GetMapping(value = "/exports/orders", produces = "text/csv")
    public ResponseEntity<byte[]> orders(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> params) {
        if (params.containsKey("after") || params.containsKey("limit")) throw AdminQuery.invalid();
        var query = AdminQueryParameters.parse("order", params);
        var csv = new OrdersCsv();
        orders.scan(actor(jwt).tenantId(), query, csv::append);
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("orders.csv").build().toString())
                .body(csv.content());
    }

    /** 在同一事务内导入已校验的草稿清单，原幂等键可安全重试。 */
    @PostMapping("/products/bulk-import")
    public CatalogBulkService.Result productImport(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ImportRows input) {
        return bulk.importDrafts(actor(jwt), key, input.items().stream().map(row -> new CatalogBulkService.Draft(
                row.productNo(), row.name(), row.skuNo(), row.unitPrice(), row.currency())).toList());
    }

    /** 同一批只允许一个目录动作，每项携带申请人刚读到的版本。 */
    @PostMapping("/products/bulk-state")
    public CatalogBulkService.Result productState(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody StateRows input) {
        return bulk.changeState(actor(jwt), key, input.action(), input.items().stream()
                .map(row -> new CatalogBulkService.Selection(row.productId(), row.version())).toList());
    }

    /** 导入数据使用明确字段，单批限制防止长时间持有写事务。 */
    public record ImportRows(@NotNull @Size(min = 1, max = CatalogBulkService.MAX_ROWS) List<@NotNull @Valid DraftInput> items) {}
    /** 单行校验与既有商品草稿契约一致。 */
    public record DraftInput(@NotBlank @Size(max = 64) String productNo, @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 64) String skuNo, @NotNull @PositiveOrZero Long unitPrice, @NotBlank String currency) {}
    /** 状态批次明确动作及对象，禁止调用者输入任意目标状态。 */
    public record StateRows(@NotNull CatalogBulkService.Action action,
            @NotNull @Size(min = 1, max = CatalogBulkService.MAX_ROWS) List<@NotNull @Valid StateInput> items) {}
    /** 每行的版本用于原商品状态迁移门禁。 */
    public record StateInput(@NotBlank String productId, @NotNull @PositiveOrZero Long version) {}

    private static Actor actor(Jwt jwt) {
        return new Actor(AdminQuery.number(jwt.getClaimAsString("tenant_id"), false), jwt.getIssuer().toString(), jwt.getSubject());
    }

    private static final class OrdersCsv {
        private static final String NEWLINE = "\r\n";
        private final StringBuilder body = new StringBuilder("\uFEFF订单号,订单状态,资金状态,应付额(最小单位),币种,创建时间(UTC)\r\n");
        private int rows;

        private void append(AdminOrderView row) {
            if (++rows > MAX_EXPORT_ROWS) {
                throw new TradeException("INVALID_ARGUMENT", "导出范围超过 50,000 条，请缩小查询范围");
            }
            column(row.businessNo() == null ? row.orderNo() : row.businessNo());
            column(row.status());
            column(row.financialStatus());
            column(Long.toString(row.payableAmount()));
            column(row.currency());
            quoted(row.createdAt().toString());
            body.append(NEWLINE);
        }

        private void column(String value) {
            quoted(value);
            body.append(',');
        }

        private void quoted(String value) {
            // 表格软件可能将已加双引号的文本仍当成公式，先按文本单元格保护。
            String safe = value.stripLeading();
            if (!safe.isEmpty() && "=+-@\t\r\n".indexOf(safe.charAt(0)) >= 0) value = "'" + value;
            body.append('"').append(value.replace("\"", "\"\"")).append('"');
        }

        private byte[] content() { return body.toString().getBytes(StandardCharsets.UTF_8); }
    }
}
