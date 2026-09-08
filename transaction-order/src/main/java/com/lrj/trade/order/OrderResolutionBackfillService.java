package com.lrj.trade.order;

import com.lrj.trade.core.*;
import com.lrj.trade.order.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 升级补漏覆盖无支付尝试订单；旧写入及在途事务退出后必须从零遍历所有租户。 */
@Service
public class OrderResolutionBackfillService {
    private final OrderMapper orders;
    private final JobSchedulerPort jobs;
    private final TransactionTemplate tx;

    /** 每页读已提交短事务，禁止跨页历史快照遗漏晚提交订单。 */
    public OrderResolutionBackfillService(OrderMapper orders,JobSchedulerPort jobs,PlatformTransactionManager transactions) {
        this.orders=orders; this.jobs=jobs; this.tx=new TransactionTemplate(transactions);
        this.tx.setTimeout(10); this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 返回本页末尾稳定主键，无更多返回原游标；重跑不会重启已完成订单任务。 */
    @Transactional(propagation=Propagation.NEVER)
    public long enqueuePage(long tenant,long after,int limit) {
        Checks.positive(tenant);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","分页参数无效");
        return tx.execute(status->{
            long last=after;
            for(var order:orders.resolutionCandidates(tenant,after,limit)) {
                if(order.payableAmount()>0 && "CLOSING".equals(order.status())) jobs.wake(tenant,JobType.ORDER_RESOLVE,order.orderNo());
                else if(order.payableAmount()>0 && "PENDING_PAYMENT".equals(order.status()))
                    jobs.schedule(tenant,JobType.ORDER_RESOLVE,order.orderNo(),order.expiresAt());
                last=Long.parseLong(order.orderId());
            }
            return last;
        });
    }
}
