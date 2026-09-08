package com.lrj.trade.member.mapper;

import com.lrj.trade.member.model.PaidOrderFact;
import com.lrj.trade.member.model.TradeFact;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 消费事实持久化边界；会员行状态不参与资金确认，避免冻结阻断已发生收款。 */
@Mapper
public interface MemberTradeFactMapper {
    /** 注册时已建立的事实行是同会员并发首单裁决点，不临时竞争创建锁行。 */
    List<TradeFact> lockFact(@Param("tenant") long tenant,@Param("member") long member);
    /** 单语句读取一致的消费快照，不拆分读取计数和首单字段。 */
    List<TradeFact> readFact(@Param("tenant") long tenant,@Param("member") long member);
    /** 使用当前读发现其他事务刚提交的相同订单，避免可重复读旧快照绕过去重。 */
    List<PaidOrderFact> lockPaidOrder(@Param("tenant") long tenant,@Param("member") long member,@Param("order") long order);
    /** 追加订单级永久去重事实，不依赖会过期的HTTP幂等响应记录。 */
    int insertPaidOrder(@Param("tenant") long tenant,@Param("member") long member,@Param("order") long order,
        @Param("amount") long amount,@Param("currency") String currency,@Param("confirmed") Instant confirmed,
        @Param("first") boolean first,@Param("policy") String policy,@Param("factVersion") long factVersion);
    /** 行锁加版本条件原子累加，首次字段仅在从未支付时设置，不提供清除历史的方法。 */
    int advanceFact(@Param("tenant") long tenant,@Param("member") long member,@Param("order") long order,
        @Param("confirmed") Instant confirmed,@Param("version") long version);
}
