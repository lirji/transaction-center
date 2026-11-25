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

/** 固定结算机器提交来自资金服务的预算签名。 */
@RestController
@ConditionalOnProperty(name = {"trade.checkout-pilot.enabled", "trade.checkout-pilot.funding-enabled"}, havingValue = "true")
public class CheckoutFundingReceiptController {
    public static final String PATH = CheckoutPilotQuoteController.PATH + "/{reference}:funding";
    private final CheckoutFundingReceiptService service;

    /** 试点关闭时不创建写入口。 */
    public CheckoutFundingReceiptController(CheckoutFundingReceiptService service) { this.service = service; }

    /** 身份和租户由已验证令牌获得，不能由请求体指定。 */
    @PostMapping(PATH)
    public CheckoutFundingReceiptService.Accepted accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference,
            @Valid @RequestBody Receipt body) {
        if (!reference.matches(CheckoutPilotQuoteService.QUOTE_ID_PATTERN)) {
            throw new TradeException("INVALID_ARGUMENT", "试点交易报价引用格式无效");
        }
        var actor = new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")), jwt.getIssuer().toString(), jwt.getSubject());
        return service.accept(actor, reference, body.fundingReceipt());
    }

    /** 只接受已签预算凭证，其他订单、金额字段从本地读取。 */
    public record Receipt(@NotBlank @Size(max = CheckoutFundingReceiptService.MAX_RECEIPT) String fundingReceipt) { }
}
