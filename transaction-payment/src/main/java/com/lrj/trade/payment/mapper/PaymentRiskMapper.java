package com.lrj.trade.payment.mapper;

import com.lrj.trade.payment.model.RiskEvaluation;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 风险评估只读历史和一次性结果写入，业务加锁与许可由服务控制。 */
@Mapper
public interface PaymentRiskMapper {
    /** 按支付尝试当前指针精确读取，避免空结果范围锁；调用方先持有订单锁。 */
    List<RiskEvaluation> latest(@Param("tenant") long tenant,@Param("request") String request);
    /** 追加成功后切换本尝试指针，复合外键防跨租户及跨尝试引用。 */
    int point(@Param("tenant") long tenant,@Param("request") String request,@Param("evaluation") String evaluation);
    /** 追加冻结输入，旧轮次与已作出的结论永久保留。 */
    int insert(@Param("evaluation") String evaluation,@Param("tenant") long tenant,@Param("attempt") long attempt,@Param("request") String request,@Param("order") long order,@Param("member") long member,@Param("channel") String channel,@Param("merchant") String merchant,@Param("amount") long amount,@Param("currency") String currency,@Param("hash") String hash,@Param("now") Instant now,@Param("deadline") Instant deadline);
    /** 只允许PENDING一次转为决定状态，不能覆盖已消费的允许或拒绝结果。 */
    int decide(@Param("tenant") long tenant,@Param("evaluation") String evaluation,@Param("state") String state,
        @Param("decision") String decision,@Param("rule") String rule,@Param("model") String model,@Param("until") Instant until);
}
