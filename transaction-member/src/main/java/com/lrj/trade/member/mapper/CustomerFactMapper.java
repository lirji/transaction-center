package com.lrj.trade.member.mapper;
import java.util.List;
import org.apache.ibatis.annotations.*;
import com.lrj.trade.member.model.CustomerFact;
/** 主库会员注册及不可逆消费事实读取。 */
@Mapper
public interface CustomerFactMapper {
    /** 普通一致读，不锁行或加入不同事务。 */
    List<CustomerFact> read(@Param("tenant") long tenant,@Param("member") long member);
}
