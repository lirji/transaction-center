package com.lrj.trade.core;

/** 领域只提交业务引用，任务持久化由独立模块实现，防止支付反向依赖任务执行器。 */
public interface JobSchedulerPort {
    /** 必须加入业务事务；重复提交不重置已完成或待人工核对任务。 */
    void enqueue(long tenant, JobType type, String businessKey);

    /** 业务事务内首次预约UTC执行时间；重放不改变原任务或重启终态。 */
    void schedule(long tenant,JobType type,String businessKey,java.time.Instant runAt);

    /** 业务事务内唤醒；运行中也持久记录新唤醒，旧工作者退避不得吞掉它。 */
    void wake(long tenant,JobType type,String businessKey);
}
