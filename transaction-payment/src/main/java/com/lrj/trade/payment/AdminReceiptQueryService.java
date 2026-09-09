package com.lrj.trade.payment;
import com.lrj.trade.payment.mapper.*;
import com.lrj.trade.payment.model.*;
import com.lrj.trade.core.*;
import com.lrj.trade.core.query.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 独立管理读取边界，保留会员服务原有本人过滤，读取不驱动领域状态。 */
@Service
@Transactional(readOnly=true,timeout=3,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW)
public class AdminReceiptQueryService {
    private final AdminReceiptMapper mapper;private final Clock clock;
    /** 仅注入本领域只读仓储；不调用网络渠道或任务写端口。 */
    public AdminReceiptQueryService(AdminReceiptMapper mapper,Clock clock) { this.mapper=mapper;this.clock=clock; }
    /** 父资源校验和分页共用同一快照，不以有无子项推断父资源归属。 */
    public AdminPage<AdminReceiptView> page(long tenant,AdminQuery query) {
        Checks.positive(tenant);Instant at=clock.instant();
        return AdminPage.of(mapper.page(tenant,query),query.limit(),at);
    }
    /** 同租户精确读取，未知与外租户资源统一不存在。 */
    public AdminReceiptView read(long tenant,String number) {
        Checks.positive(tenant);return mapper.find(tenant,number).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","资源不存在"));
    }

    /** 详情与子记录使用一个RR快照，资金和验收事实不由页面拼凑裁决。 */
    public Detail detail(long tenant,String number) {
        Checks.positive(tenant);Instant at=clock.instant();
        var view=mapper.find(tenant,number).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","资源不存在"));
        return new Detail(view,at);
    }
    /** 展开安全主记录，字段兼容列表，不展开内部持久化实体。 */
    public record Detail(@com.fasterxml.jackson.annotation.JsonUnwrapped AdminReceiptView summary,Instant observedAt) {}
}
