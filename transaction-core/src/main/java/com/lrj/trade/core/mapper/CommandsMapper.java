package com.lrj.trade.core.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.lrj.trade.core.model.*;

/** Commands的持久化边界；SQL仅位于同名XML，事务由业务层统一编排。 */
@Mapper
public interface CommandsMapper {
    /** 唯一键建立命令占位，与业务操作共用事务，重复键不能覆盖原摘要。 */
    int insertIfAbsent(@Param("tenant") long tenant, @Param("actorKey") String actorKey, @Param("operation") String operation, @Param("requestKey") String requestKey, @Param("requestHash") String requestHash);

    /** 只读完成结果用于外部验签前的原请求回放，不替代锁定写入。 */
    CommandRow findCommand(@Param("tenant") long tenant, @Param("actorKey") String actorKey, @Param("operation") String operation, @Param("requestKey") String requestKey);

    /** 当前读锁定幂等记录，等待并发提交后读取已保存结果。 */
    CommandRow lockCommand(@Param("tenant") long tenant, @Param("actorKey") String actorKey, @Param("operation") String operation, @Param("requestKey") String requestKey);

    /** 业务成功后保存引用，不能单独提交幂等成功状态。 */
    int saveResult(@Param("result") String result, @Param("tenant") long tenant, @Param("actorKey") String actorKey, @Param("operation") String operation, @Param("requestKey") String requestKey);
}
