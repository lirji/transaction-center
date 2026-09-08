package com.lrj.trade.member.mapper;
import java.util.List;
import org.apache.ibatis.annotations.*;
import com.lrj.trade.member.model.MemberPaidEvidenceRow;
/** 本域事实的有界集合读取，SQL不跨越领域表所有权。 */
@Mapper
public interface MemberPaidEvidenceMapper {
    /** 同一主库事务的普通快照读，禁止锁定读取改变快照语义。 */
    List<MemberPaidEvidenceRow> read(@Param("tenant") long tenant,@Param("orders") List<Long> orders);
}
