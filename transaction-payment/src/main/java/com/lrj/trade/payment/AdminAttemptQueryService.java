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
public class AdminAttemptQueryService {
    private final AdminAttemptMapper mapper;private final Clock clock;private final AdminPaymentMapper payments;
    /** 使用同一只读快照读取总量与指定页，并复用列表投影。 */
    public AdminNumberedPage<AdminAttemptView> numberedPage(long tenant, AdminQuery query, long page, String paymentNo) {
        Checks.positive(tenant);
        if (payments.find(tenant, paymentNo).isEmpty()) {
            throw new TradeException("NOT_FOUND", "支付不存在");
        }
        var at = clock.instant();
        return AdminNumberedPage.of(page, query.limit(), mapper.pageCount(tenant, query, paymentNo), at,
                offset -> mapper.pageAtOffset(tenant, query, paymentNo, offset));
    }

    /** 仅注入本领域只读仓储；不调用网络渠道或任务写端口。 */
    public AdminAttemptQueryService(AdminAttemptMapper mapper,Clock clock,AdminPaymentMapper payments) { this.mapper=mapper;this.clock=clock;this.payments=payments; }
    /** 父资源校验和分页共用同一快照，不以有无子项推断父资源归属。 */
    public AdminPage<AdminAttemptView> page(long tenant,AdminQuery query,String paymentNo) {
        Checks.positive(tenant);Instant at=clock.instant();if(payments.find(tenant,paymentNo).isEmpty()) throw new TradeException("NOT_FOUND","支付不存在");
        return AdminPage.of(mapper.page(tenant,query,paymentNo),query.limit(),at);
    }
    /** 同租户精确读取，未知与外租户资源统一不存在。 */
    public AdminAttemptView read(long tenant,String number) {
        Checks.positive(tenant);return mapper.find(tenant,number).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","资源不存在"));
    }
}
