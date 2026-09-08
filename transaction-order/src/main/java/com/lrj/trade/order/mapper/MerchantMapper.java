package com.lrj.trade.order.mapper;

import com.lrj.trade.order.model.Merchant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 租户商户配置持久化接口，编号创建后不可覆盖。 */
@Mapper
public interface MerchantMapper {
    /** 每租户仅一份配置，唯一键阻止并发创建另一收款主体。 */
    int insert(@Param("tenant") long tenant,@Param("number") String number,@Param("name") String name);
    /** 管理读取包含冻结状态，不泄露其他租户配置。 */
    List<Merchant> find(@Param("tenant") long tenant);
    /** 新订单共享锁定商户，冻结与下单有确定先后顺序。 */
    List<Merchant> lockActive(@Param("tenant") long tenant);
    /** 版本和状态共同限制更新，冻结不修改历史订单商户。 */
    int changeStatus(@Param("tenant") long tenant,@Param("version") long version,@Param("status") String status);
}
