package com.lrj.trade.server.checkout;

import com.lrj.trade.core.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 只有明确配对的结算机器可以为已核验商城报价申请原生交易报价引用。 */
@RestController
@ConditionalOnProperty(name="trade.checkout-pilot.enabled", havingValue="true")
public class CheckoutPilotQuoteController {
    public static final String PATH = "/internal/v1/checkout-pilot/quote-references";
    private final CheckoutPilotQuoteService quotes;

    /** 权限和持久化统一由试点用例处理，控制器只映射已认证请求。 */
    public CheckoutPilotQuoteController(CheckoutPilotQuoteService quotes) {
        this.quotes = quotes;
    }

    /** 核验主体引用和持久报价，重复请求按原报价恢复同一交易引用。 */
    @PostMapping(PATH)
    public CheckoutPilotQuoteService.Prepared prepare(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Input body) {
        var caller = new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")), jwt.getIssuer().toString(), jwt.getSubject());
        return quotes.prepare(caller, body.commerceQuoteId(), body.ownerRef());
    }

    /** 请求只定位当前结算，门店、金额和租户均分别来自实际授权和商城报价。 */
    public record Input(@NotBlank @Pattern(regexp=CheckoutPilotQuoteService.QUOTE_ID_PATTERN) String commerceQuoteId,
                        @NotBlank @Pattern(regexp=CheckoutPilotQuoteService.DIGEST_PATTERN) String ownerRef) { }
}
