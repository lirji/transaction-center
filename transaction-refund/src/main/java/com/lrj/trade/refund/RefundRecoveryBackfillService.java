package com.lrj.trade.refund;

import com.lrj.trade.core.*;
import com.lrj.trade.refund.mapper.RefundMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 滚动升级后从零有界补漏；只读本域申请，不能跨域扫描任务表或重启终态任务。 */
@Service
public class RefundRecoveryBackfillService {
    private final RefundMapper mapper;
    private final JobSchedulerPort jobs;
    private final TransactionTemplate tx;
    /** 每页读取最新提交事实，防旧写入跨分页提交时沿用过期快照。 */
    public RefundRecoveryBackfillService(RefundMapper mapper,JobSchedulerPort jobs,PlatformTransactionManager transactions) {
        this.mapper=mapper; this.jobs=jobs; tx=new TransactionTemplate(transactions);
        tx.setTimeout(10); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 返回本批末尾主键，未读到更多记录返回原游标；旧写入完全退出后必须从零收尾。 */
    @Transactional(propagation=Propagation.NEVER)
    public long enqueuePage(long tenant,long after,int limit) {
        Checks.positive(tenant);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","退款补漏游标或批次无效");
        return tx.execute(s->{
            long last=after;
            for(var row:mapper.recoveryCandidates(tenant,after,limit)) {
                if(row.status().equals("PENDING_APPROVAL")) jobs.schedule(tenant,JobType.REFUND_RECOVER,row.refundNo(),row.approvalDeadline());
                // 旧版本批准可能没有唤醒V016已预约的未来任务；必须提前现有任务，不能只补不存在的键。
                else jobs.wake(tenant,JobType.REFUND_RECOVER,row.refundNo());
                last=row.refundId();
            }
            return last;
        });
    }
}
