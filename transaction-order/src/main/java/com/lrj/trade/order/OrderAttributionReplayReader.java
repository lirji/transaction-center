package com.lrj.trade.order;

import com.lrj.trade.order.mapper.OrderAttributionMapper;
import com.lrj.trade.order.model.OrderAttributionReplay;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 无令牌旧组合事务可能已有RR快照；短只读事务查询已提交永久事实，不锁不存在的键范围。 */
@Service
public class OrderAttributionReplayReader {
    private final OrderAttributionMapper mapper;
    /** 仅依赖归因读取Mapper，禁止本入口写业务数据。 */
    public OrderAttributionReplayReader(OrderAttributionMapper mapper) { this.mapper=mapper; }
    /** 命令键锁由外层持有；独立读不等待业务锁，超时失败不会继续创建订单。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,timeout=2)
    public OrderAttributionReplay latest(long tenant,String actorKey,String operation,String key) {
        return mapper.replay(tenant,actorKey,operation,key);
    }
}
