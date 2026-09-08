package com.lrj.trade.payment.mapper;

import com.lrj.trade.payment.model.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 支付领域持久化边界，事务和业务状态判断由服务编排。 */
@Mapper
public interface ChannelMapper {
    /** 追加不可变渠道绑定，数据库唯一键阻止收款主体跨租户复用。 */
    int insert(@Param("tenant") long tenant,@Param("merchant") String merchant,@Param("code") String code,@Param("channelMerchant") String channelMerchant,@Param("currency") String currency,@Param("credential") String credential,@Param("returnId") String returnId,@Param("returnUrl") String returnUrl,@Param("now") Instant now);
    /** 共享锁定可用配置，禁止新尝试绕过渠道启停。 */
    List<ChannelConfiguration> lockActive(@Param("tenant") long tenant,@Param("merchant") String merchant,@Param("code") String code,@Param("currency") String currency);
    /** 历史查询不受冻结影响，合法收退款仍依赖原绑定。 */
    List<ChannelConfiguration> findById(@Param("tenant") long tenant,@Param("channel") long channel);
    /** 管理端有界分页，仅当前租户配置。 */
    List<ChannelConfiguration> list(@Param("tenant") long tenant,@Param("after") long after,@Param("limit") int limit);
}
