package com.lrj.trade.core.mapper;
import com.lrj.trade.core.AdmissionPolicy;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 策略锁总是在领域锁之前取得，管理更新不反向访问资金表。 */
@Mapper
public interface AdmissionMapper {
    /** 普通管理读取不要求事务。 */
    List<AdmissionPolicy> find(@Param("tenant") long tenant);
    /** RR共享锁覆盖整次受理，包括缺失策略的首次暂停边界。 */
    List<AdmissionPolicy> share(@Param("tenant") long tenant);
    /** 首次管理更新占租户唯一键，已有策略绝不重置。 */
    void ensure(@Param("tenant") long tenant);
    /** 管理更新当前读，避免快照版本落后。 */
    AdmissionPolicy lock(@Param("tenant") long tenant);
    /** 条件推进版本，防止过期配置覆盖新策略。 */
    int update(@Param("tenant") long tenant,@Param("version") long version,@Param("orders") boolean orders,@Param("payments") boolean payments);
}
