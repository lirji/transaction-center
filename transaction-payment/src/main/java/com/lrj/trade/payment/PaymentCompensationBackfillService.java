package com.lrj.trade.payment;

import com.lrj.trade.core.*;
import com.lrj.trade.payment.mapper.PaymentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 旧版本支付写入退出后按租户从零补漏；不依赖异常事件被外部消费者读到。 */
@Service
public class PaymentCompensationBackfillService {
    private final PaymentMapper mapper;
    private final JobSchedulerPort jobs;
    private final TransactionTemplate tx;
    /** 每页独立RC事务避免跨页快照遗漏新提交的异常收款，写任务与页读取同事务。 */
    public PaymentCompensationBackfillService(PaymentMapper mapper,JobSchedulerPort jobs,PlatformTransactionManager transactions) {
        this.mapper=mapper; this.jobs=jobs; tx=new TransactionTemplate(transactions);
        tx.setTimeout(10); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 最多扫描100笔收款并返回末尾主键；正常记录也推进游标，不能为找异常无界扫描。 */
    @Transactional(propagation=Propagation.NEVER)
    public long enqueuePage(long tenant,long after,int limit) {
        Checks.positive(tenant);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","补偿扫描游标或批次无效");
        return tx.execute(status->{
            long last=after;
            for(var candidate:mapper.compensationCandidates(tenant,after,limit)) {
                if(!candidate.disposition().equals("NORMAL")) jobs.enqueue(tenant,JobType.REFUND_COMPENSATE,candidate.receiptNo());
                last=candidate.receiptId();
            }
            return last;
        });
    }
}
