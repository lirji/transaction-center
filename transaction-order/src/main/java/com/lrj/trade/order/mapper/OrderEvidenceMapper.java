package com.lrj.trade.order.mapper;
import java.util.List;
import org.apache.ibatis.annotations.*;
import com.lrj.trade.order.model.OrderEvidenceRow;
/** 本域事实的有界集合读取，SQL不跨越领域表所有权。 */
@Mapper
public interface OrderEvidenceMapper {
    /** 同一主库事务的普通快照读，禁止锁定读取改变快照语义。 */
    List<OrderEvidenceRow> read(@Param("tenant") long tenant,@Param("orders") List<Long> orders);
}
