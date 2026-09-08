package com.lrj.trade.payment;

import com.lrj.trade.core.*;
import com.lrj.trade.payment.mapper.PaymentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 滚动升级收尾补漏；旧版写入完全退出后按租户从零分页扫描，重跑不重启终态。 */
@Service
public class PaymentRecoveryBackfillService {
    private final PaymentMapper mapper;
    private final JobSchedulerPort jobs;
    private final TransactionTemplate tx;
    /** 支付读取自身不可变尝试，任务经公共Port入队，不跨域读取任务表。 */
    public PaymentRecoveryBackfillService(PaymentMapper mapper, JobSchedulerPort jobs, PlatformTransactionManager transactions) {
        this.mapper=mapper; this.jobs=jobs;
        this.tx=new TransactionTemplate(transactions);
        this.tx.setTimeout(10);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 返回本批最后主键，无更多记录返回原游标；调用方须遍历全部租户并保留收尾证据。 */
    @Transactional(propagation=Propagation.NEVER)
    public long enqueuePage(long tenant, long afterId, int limit) {
        Checks.positive(tenant);
        if(afterId<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","补漏游标或批次无效");
        // 每页独立读取当前已提交尝试，禁止沿用外层旧快照或一次长事务扫描全部页。
        return tx.execute(status->{
            long last=afterId;
            for(var attempt:mapper.recoveryCandidates(tenant,afterId,limit)) {
                jobs.enqueue(tenant,JobType.PAYMENT_RECOVER,attempt.requestNo());
                last=Long.parseLong(attempt.attemptId());
            }
            return last;
        });
    }
}
