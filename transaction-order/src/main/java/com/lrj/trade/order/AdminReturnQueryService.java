package com.lrj.trade.order;
import com.lrj.trade.order.mapper.*;
import com.lrj.trade.order.model.*;
import com.lrj.trade.core.*;
import com.lrj.trade.core.query.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 独立管理读取边界，保留会员服务原有本人过滤，读取不驱动领域状态。 */
@Service
@Transactional(readOnly=true,timeout=3,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW)
public class AdminReturnQueryService {
    private final AdminReturnMapper mapper;private final Clock clock;
    /** 使用同一只读快照读取总量与指定页，并复用列表投影。 */
    public AdminNumberedPage<AdminReturnView> numberedPage(long tenant, AdminQuery query, long page) {
        Checks.positive(tenant);
        var at = clock.instant();
        return AdminNumberedPage.of(page, query.limit(), mapper.pageCount(tenant, query), at,
                offset -> mapper.pageAtOffset(tenant, query, offset));
    }

    /** 仅注入本领域只读仓储；不调用网络渠道或任务写端口。 */
    public AdminReturnQueryService(AdminReturnMapper mapper,Clock clock) { this.mapper=mapper;this.clock=clock; }
    /** 父资源校验和分页共用同一快照，不以有无子项推断父资源归属。 */
    public AdminPage<AdminReturnView> page(long tenant,AdminQuery query) {
        Checks.positive(tenant);Instant at=clock.instant();
        return AdminPage.of(mapper.page(tenant,query),query.limit(),at);
    }
    /** 同租户精确读取，未知与外租户资源统一不存在。 */
    public AdminReturnView read(long tenant,String number) {
        Checks.positive(tenant);return mapper.find(tenant,number).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","资源不存在"));
    }

    /** 详情与子记录使用一个RR快照，资金和验收事实不由页面拼凑裁决。 */
    public Detail detail(long tenant,String number) {
        Checks.positive(tenant);Instant at=clock.instant();
        var view=mapper.find(tenant,number).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","资源不存在"));
        return new Detail(view,at,view.receiptNo()==null?null:mapper.inspection(tenant,view.receiptNo()).stream().findFirst().orElseThrow(()->new TradeException("EVIDENCE_UNAVAILABLE","验收事实不完整")));
    }
    /** 展开安全主记录，字段兼容列表，不展开内部持久化实体。 */
    public record Detail(@com.fasterxml.jackson.annotation.JsonUnwrapped AdminReturnView summary,Instant observedAt,AdminInspectionView inspection) {}
}
