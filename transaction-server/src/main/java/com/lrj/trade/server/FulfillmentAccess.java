package com.lrj.trade.server;
import com.lrj.trade.core.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** 已验签scope之外再限定真实履约和仓储主体，默认关闭所有写入口。 */
@Component
public class FulfillmentAccess {
    private final boolean enabled;private final Set<String> delivery,completion,returns;
    /** 三类来源独立白名单，不把普通管理员当成真实交付服务。 */
    public FulfillmentAccess(@Value("${trade.fulfillment.enabled:false}") boolean enabled,@Value("${trade.fulfillment.allowed-delivery-actor-keys:}") String delivery,@Value("${trade.fulfillment.allowed-completion-actor-keys:}") String completion,@Value("${trade.fulfillment.allowed-return-actor-keys:}") String returns) {
        this.enabled=enabled;this.delivery=keys(delivery);this.completion=keys(completion);this.returns=keys(returns);
        if(enabled && (this.delivery.isEmpty() || this.completion.isEmpty() || this.returns.isEmpty())) throw new IllegalArgumentException("启用履约入口必须明确三类可信主体");
    }
    /** 会员确认仅开放既有真实交付，不允许会员新增交付证据。 */
    public void memberEnabled() {if(!enabled) throw new TradeException("FULFILLMENT_DISABLED","履约入口尚未启用");}
    /** 核验已认证主体的对应来源权限，调用方不能自报角色。 */
    public void require(Actor actor,String operation) {
        memberEnabled();Set<String> allowed=switch(operation) {case "DELIVER"->delivery;case "COMPLETE"->completion;case "RETURN"->returns;default->throw new IllegalArgumentException("未知来源类型");};
        if(!allowed.contains(actor.key())) throw new TradeException("FULFILLMENT_UNTRUSTED","调用者不是该操作的可信履约或仓储主体");
    }
    private Set<String> keys(String text) {Set<String> result=text.isBlank()?Set.of():Set.copyOf(Arrays.asList(text.split(",",-1)));if(result.stream().anyMatch(key->!key.matches("[a-f0-9]{64}"))) throw new IllegalArgumentException("可信主体摘要配置无效");return result;}
}
