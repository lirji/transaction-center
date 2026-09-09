package com.lrj.trade.server;
import com.lrj.trade.core.TradeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** 管理员证据入口独立开关，不改变可信内部服务的EvidenceAccess允许列表。 */
@Component
public class AdminEvidenceAccess {
    private final boolean enabled;
    /** 默认关闭，授权部署后才可查询事实；HTTP过滤链另校验双scope。 */
    public AdminEvidenceAccess(@Value("${trade.admin-evidence.enabled:false}") boolean enabled) { this.enabled=enabled; }
    /** 不因管理员有普通管理权限而隐式开启证据读取。 */
    public void require() { if(!enabled) throw new TradeException("EVIDENCE_DISABLED","管理证据读取尚未启用"); }
}
