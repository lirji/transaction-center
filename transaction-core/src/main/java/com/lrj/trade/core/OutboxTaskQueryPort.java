package com.lrj.trade.core;
import com.lrj.trade.core.query.AdminNumberedPage;
import java.time.Instant;
import java.util.List;
/** 包含无对应事件的异常发布任务，避免毒任务从事件队列中消失。 */
public interface OutboxTaskQueryPort {
    /** 后台限定租户/状态的有界摘要，不返回非规范业务键原文或工作者身份。 */
    /** 仅返回发布任务安全摘要的编号页。 */
    AdminNumberedPage<View> numberedPage(long tenant, String state, int limit, long page);
    List<View> page(long tenant,String state,long after,int limit);
    /** 规范UUID可定位事件；异常键只返回摘要供内部排查，防止泄露报文。 */
    record View(String id,String eventId,String referenceHash,String status,long attempts,Instant nextRunAt,String lastError) {}
}
