package com.lrj.trade.server.checkout;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 将独立的试点冻结权限收口到一个交易内部入口。 */
@RestController
@ConditionalOnProperty(name = {"trade.checkout-pilot.enabled", "trade.checkout-pilot.freeze-enabled"}, havingValue = "true")
public class CheckoutPilotPriceController {
    public static final String PATH = CheckoutPilotQuoteController.PATH + "/{reference}:freeze";
    private final CheckoutPilotPriceService prices;

    /** 冻结规则由交易用例统一处理。 */
    public CheckoutPilotPriceController(CheckoutPilotPriceService prices) {
        this.prices = prices;
    }

    /** 只接受引用与原始签名，金额与权限由服务端核对。 */
    @PostMapping(PATH)
    public CheckoutPilotPriceService.Receipt freeze(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference,
            @Valid @RequestBody Input body) {
        if (!reference.matches(CheckoutPilotQuoteService.QUOTE_ID_PATTERN)) {
            throw new TradeException("INVALID_ARGUMENT", "试点交易报价引用格式无效");
        }
        var caller = new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")), jwt.getIssuer().toString(), jwt.getSubject());
        return prices.freeze(caller, reference, body.checkoutQuoteToken());
    }

    /** 使用受限长度的签价凭证作为冻结请求。 */
    public record Input(@NotBlank @Size(max = CheckoutPriceProof.MAX_PROOF_SIZE) String checkoutQuoteToken) { }
}
