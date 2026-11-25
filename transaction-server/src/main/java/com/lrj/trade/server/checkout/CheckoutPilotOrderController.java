package com.lrj.trade.server.checkout;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.order.model.OrderView;
import com.lrj.trade.order.model.ShippingAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 买家与营销机器分别使用原生下单和订单证据接口。 */
@RestController
@ConditionalOnProperty(name = {"trade.checkout-pilot.enabled", "trade.checkout-pilot.freeze-enabled",
    "trade.checkout-pilot.order-enabled"}, havingValue = "true")
public class CheckoutPilotOrderController {
    public static final String CREATE_PATH = "/api/v1/trade/checkout-pilot/orders";
    public static final String EVIDENCE_PATH = CheckoutPilotQuoteController.PATH + "/{reference}/order-evidence";
    private final CheckoutPilotOrderService orders;

    /** 订单金额和身份只从服务端证据及认证上下文读取。 */
    public CheckoutPilotOrderController(CheckoutPilotOrderService orders) {
        this.orders = orders;
    }

    /** 客户端提交原交易报价引用与实物履约信息，返回标准原生订单。 */
    @PostMapping(CREATE_PATH)
    public OrderView create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Input body) {
        Long warehouse = null;
        if (body.warehouseId() != null) {
            try {
                warehouse = Long.parseLong(body.warehouseId());
            } catch (NumberFormatException invalid) {
                throw new TradeException("INVALID_ARGUMENT", "仓库编号超出范围");
            }
        }
        return orders.create(actor(jwt), key, body.tradeQuoteRef(), warehouse, body.address());
    }

    /** 管理支付与预留的机器可核对订单来源，响应禁止缓存。 */
    @GetMapping(EVIDENCE_PATH)
    public ResponseEntity<CheckoutPilotOrderService.Evidence> evidence(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String reference) {
        if (!reference.matches(CheckoutPilotQuoteService.QUOTE_ID_PATTERN)) {
            throw new TradeException("INVALID_ARGUMENT", "试点交易报价引用格式无效");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.evidence(actor(jwt), reference));
    }

    private static Actor actor(Jwt jwt) {
        return new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")), jwt.getIssuer().toString(), jwt.getSubject());
    }

    /** 报价身份由交易分配，用户只补充仓库及地址。 */
    public record Input(@NotBlank @Pattern(regexp = CheckoutPilotQuoteService.QUOTE_ID_PATTERN) String tradeQuoteRef,
                        @Pattern(regexp = "[1-9][0-9]{0,18}") String warehouseId, ShippingAddress address) { }
}
