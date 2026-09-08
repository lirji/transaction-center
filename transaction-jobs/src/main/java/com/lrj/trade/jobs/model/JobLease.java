package com.lrj.trade.jobs.model;

import java.time.Instant;

/** 领取时的不可变租约凭据；接管后旧对象自然失效，不能仅按任务主键提交结果。 */
public record JobLease(String jobId, long tenantId, String jobType, String businessKey,
                       String owner, long fencingToken, long attempts, Instant leaseUntil,String traceParent) {
    /** 旧内部调用缺少持久上下文时按历史任务处理，不伪造上游关系。 */
    public JobLease(String jobId,long tenantId,String jobType,String businessKey,String owner,long fencingToken,long attempts,Instant leaseUntil) {
        this(jobId,tenantId,jobType,businessKey,owner,fencingToken,attempts,leaseUntil,null);
    }
}
