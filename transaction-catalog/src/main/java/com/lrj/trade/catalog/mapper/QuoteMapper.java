package com.lrj.trade.catalog.mapper;

import com.lrj.trade.catalog.model.QuoteHeader;
import com.lrj.trade.catalog.model.QuoteItem;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 报价持久化边界，主表及明细必须与幂等结果在同一本地事务提交。 */
@Mapper
public interface QuoteMapper {
    /** 创建不可变报价，服务端时钟和定价策略版本随主表保存。 */
    int insertQuote(@Param("tenant") long tenant,@Param("member") long member,@Param("amount") long amount,
        @Param("currency") String currency,@Param("policy") String policy,@Param("created") Instant created,
        @Param("expires") Instant expires);
    /** 仅在插入报价后同一事务连接立即调用，并校验租户，禁止作为普通查号接口。 */
    Long lastInsertedQuoteId(@Param("tenant") long tenant);
    /** 追加明细快照，不提供覆盖接口，失败时必须回滚整张报价。 */
    int insertItem(@Param("tenant") long tenant,@Param("quote") long quote,@Param("sku") long sku,
        @Param("revision") long revision,@Param("title") String title,@Param("specification") String specification,
        @Param("stockType") String stockType,@Param("quantity") long quantity,@Param("price") long price,
        @Param("discount") long discount,@Param("amount") long amount,@Param("created") Instant created);
    /** 同时核对租户与会员，越权与不存在均返回空列表。 */
    List<QuoteHeader> findOwn(@Param("tenant") long tenant,@Param("member") long member,@Param("quote") long quote);
    /** 按规格主键排序，后续订单以相同顺序处理库存锁。 */
    List<QuoteItem> listItems(@Param("tenant") long tenant,@Param("quote") long quote);
}
