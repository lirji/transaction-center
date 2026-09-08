package com.lrj.trade.server;
import com.lrj.trade.core.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** 报告来源绑定与只读导出分开，调用方不能自报sourceId。 */
@Component
public class ReconciliationReportAccess {
    private final boolean enabled;private final Set<String> actors;private final String source;
    /** 逻辑来源稳定于凭据轮换，启用须明确可信主体摘要。 */
    public ReconciliationReportAccess(@Value("${trade.reconciliation.report-enabled:false}") boolean enabled,
        @Value("${trade.reconciliation.report-allowed-actor-keys:}") String keys,
        @Value("${trade.reconciliation.report-source-id:recon-platform}") String source) {
        this.enabled=enabled;this.source=source;actors=keys.isBlank()?Set.of():Set.copyOf(Arrays.asList(keys.split(",",-1)));
        if(!source.matches("[a-z][a-z0-9._-]{0,63}") || actors.stream().anyMatch(k->!k.matches("[a-f0-9]{64}")) || enabled && actors.isEmpty()) throw new IllegalArgumentException("对账报告来源配置无效");
    }
    /** 已验签与scope过滤后再核服务身份，返回服务端固定逻辑来源。 */
    public String require(Actor actor) {
        if(!enabled) throw new TradeException("RECONCILIATION_DISABLED","对账报告入口尚未启用");
        if(!actors.contains(actor.key())) throw new TradeException("RECONCILIATION_UNTRUSTED","调用者不是可信报告服务");
        return source;
    }
}
