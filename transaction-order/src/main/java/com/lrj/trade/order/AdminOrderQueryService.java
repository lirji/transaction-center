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
public class AdminOrderQueryService {
    private static final java.util.regex.Pattern ORDER_TAIL = java.util.regex.Pattern.compile("([0-9]{6})-([0-9]{4})");
    private static final java.time.format.DateTimeFormatter BUSINESS_DATE = java.time.format.DateTimeFormatter
        .ofPattern("uuMMdd", Locale.ROOT).withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private final AdminOrderMapper mapper;private final Clock clock;
    /** 仅注入本领域只读仓储；不调用网络渠道或任务写端口。 */
    public AdminOrderQueryService(AdminOrderMapper mapper,Clock clock) { this.mapper=mapper;this.clock=clock; }
    /** 父资源校验和分页共用同一快照，不以有无子项推断父资源归属。 */
    public AdminPage<AdminOrderView> page(long tenant,AdminQuery query) {
        Checks.positive(tenant);Instant at=clock.instant();
        return AdminPage.of(mapper.page(tenant,orderNumberSearch(query)),query.limit(),at);
    }
    /** 仅接受业务日与四位尾号，固定前缀用于索引且不开放输入通配符。 */
    private static AdminQuery orderNumberSearch(AdminQuery query) {
        if (!(query.filters().get("orderNo") instanceof String orderNo)) return query;
        var tail = ORDER_TAIL.matcher(orderNo);
        Map<String,Object> filters = new HashMap<>(query.filters());
        if (tail.matches()) {
            try {
                filters.put("businessDate",LocalDate.parse(tail.group(1), BUSINESS_DATE));
            } catch (DateTimeException invalid) {
                throw new TradeException("INVALID_ARGUMENT","订单尾号查询日期无效");
            }
            filters.put("numberTail",tail.group(2));
        } else if (BusinessNumber.matches(BusinessNumber.Type.ORDER,orderNo)) {
            filters.put("businessNo",orderNo);
        } else {
            return query;
        }
        filters.remove("orderNo");
        return new AdminQuery(query.after(),query.limit(),query.createdFrom(),query.createdTo(),Map.copyOf(filters),query.fetchLimit());
    }
    /** 同租户精确读取，未知与外租户资源统一不存在。 */
    public AdminOrderView read(long tenant,String number) {
        Checks.positive(tenant);return mapper.find(tenant,number,BusinessNumber.matches(BusinessNumber.Type.ORDER,number)).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","资源不存在"));
    }

    /** 详情与子记录使用一个RR快照，资金和验收事实不由页面拼凑裁决。 */
    public Detail detail(long tenant,String number) {
        Checks.positive(tenant);Instant at=clock.instant();
        var view=mapper.find(tenant,number,BusinessNumber.matches(BusinessNumber.Type.ORDER,number)).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","资源不存在"));
        return new Detail(view,at,mapper.items(tenant,view.orderId()),mapper.fulfillment(tenant,view.orderId()).stream().findFirst().orElse(null));
    }
    /** 展开安全主记录，字段兼容列表，不展开内部持久化实体。 */
    public record Detail(@com.fasterxml.jackson.annotation.JsonUnwrapped AdminOrderView summary,Instant observedAt,List<AdminOrderItem> items,AdminFulfillmentView fulfillment) {}
}
