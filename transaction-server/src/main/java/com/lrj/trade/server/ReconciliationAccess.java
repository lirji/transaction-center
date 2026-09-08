package com.lrj.trade.server;
import com.lrj.trade.core.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** 独立事实读取信任边界；JWT专用scope之外仍核对允许的服务身份，默认关闭。 */
@Component
public class ReconciliationAccess {
    private final boolean enabled;
    private final Set<String> allowed;
    /** 允许列表为issuer与subject共同摘要；租户仅使用已验签令牌声明，不能从请求体覆盖。 */
    public ReconciliationAccess(@Value("${trade.reconciliation.enabled:false}") boolean enabled,@Value("${trade.reconciliation.allowed-actor-keys:}") String keys) {
        this.enabled=enabled;
        allowed=keys.isBlank()?Set.of():Set.copyOf(Arrays.asList(keys.split(",",-1)));
        if(allowed.stream().anyMatch(k->!k.matches("[a-f0-9]{64}")) || (enabled && allowed.isEmpty()))
            throw new IllegalArgumentException("对账事实入口启用须配置有效服务身份摘要");
    }
    /** 只接受已由资源服务器验签、scope校验后的Actor，不把管理权限等同服务身份。 */
    public void require(Actor actor) {
        if(!enabled) throw new TradeException("RECONCILIATION_DISABLED","可信对账事实读取入口尚未启用");
        if(!allowed.contains(actor.key())) throw new TradeException("RECONCILIATION_UNTRUSTED","调用者不属于可信对账事实服务");
    }
}
