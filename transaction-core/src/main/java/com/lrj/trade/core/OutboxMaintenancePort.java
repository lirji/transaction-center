package com.lrj.trade.core;
import java.util.OptionalLong;
/** 后台维护只补持久任务，不发送消息；内存轮转与数据库进度分离。 */
public interface OutboxMaintenancePort {
    /** 内部全租户发现仅返回下一租户ID，不提供业务数据或HTTP入口。 */
    OptionalLong nextTenant(long after);
    /** 单次最多扫描100个事件，固定轮次上界和游标由数据库事务推进。 */
    Step scanPage(long tenant,int limit,long intervalMillis);
    /** 候选数不代表新建任务数，幂等入队可能遇到既有DONE/REVIEW。 */
    record Step(boolean worked,int scanned,int candidates,boolean completed) {}
}
