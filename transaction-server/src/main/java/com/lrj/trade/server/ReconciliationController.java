package com.lrj.trade.server;
import com.lrj.trade.core.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 仅可信财务服务读取有界资金事实，租户只能取自验签令牌。 */
@RestController
@RequestMapping("/internal/v1/reconciliation")
public class ReconciliationController {
    private final ReconciliationAccess access;
    private final ReconciliationFactsPort facts;
    /** 独立开关和服务白名单不复用营销证据读取授权。 */
    public ReconciliationController(ReconciliationAccess access,ReconciliationFactsPort facts) {this.access=access;this.facts=facts;}
    /** 空页也可能仍有候选，调用者必须按nextCursor和hasMore继续。 */
    @GetMapping("/money-facts")
    public ReconciliationFactsPort.Page scan(@AuthenticationPrincipal Jwt jwt,@RequestParam String channelId,
        @RequestParam(defaultValue="0") String after,@RequestParam(required=false) String upper,@RequestParam(defaultValue="100") int scanLimit) {
        Actor actor;
        try {actor=new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")),jwt.getIssuer().toString(),jwt.getSubject());}
        catch(RuntimeException invalid) {throw new TradeException("INVALID_IDENTITY","令牌缺少有效租户或主体声明");}
        access.require(actor);
        return facts.scan(actor.tenantId(),number(channelId,false),number(after,true),upper==null?null:number(upper,true),scanLimit);
    }
    private long number(String value,boolean zero) {
        if(value==null || !value.matches(zero?"0|[1-9][0-9]{0,18}":"[1-9][0-9]{0,18}")) throw new TradeException("INVALID_ARGUMENT","资金导出编号无效");
        try {return Long.parseLong(value);} catch(NumberFormatException invalid) {throw new TradeException("INVALID_ARGUMENT","资金导出编号超限");}
    }
}
