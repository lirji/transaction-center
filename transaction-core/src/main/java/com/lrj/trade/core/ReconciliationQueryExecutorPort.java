package com.lrj.trade.core;
/** 独立只查单执行边界，不提供创建关闭补建或预占释放动作。 */
public interface ReconciliationQueryExecutorPort {
    /** 接受操作前只读验证引用及原渠道计划，不发网络。 */
    void validate(long tenant,long channel,String action,String referenceType,String reference);
    /** 租约许可提交后事务外重新核原绑定并查询，结果不代表case结案。 */
    Outcome execute(long tenant,long channel,String action,String referenceType,String reference);
    /** 查无且可补建仍只是观察结果，调用方禁止将它解释为补建许可。 */
    enum Outcome {UNKNOWN,PENDING,UNAVAILABLE,EVIDENCE_ACCEPTED,REPLAYABLE_ABSENCE}
}
