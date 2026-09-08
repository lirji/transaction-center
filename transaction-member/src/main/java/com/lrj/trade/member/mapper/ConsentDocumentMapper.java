package com.lrj.trade.member.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.lrj.trade.member.model.*;

/** ConsentDocumentService的持久化边界；SQL仅位于同名XML，事务由业务层统一编排。 */
@Mapper
public interface ConsentDocumentMapper {
    /** 追加不可变条款版本，重复版本由唯一约束拒绝。 */
    int insertDocument(@Param("tenant") long tenant, @Param("purpose") String purpose, @Param("documentVersion") String documentVersion, @Param("title") String title, @Param("contentUrl") String contentUrl, @Param("contentHash") String contentHash);

    /** 按租户用途版本精确读取条款编号。 */
    String findDocumentId(@Param("tenant") long tenant, @Param("purpose") String purpose, @Param("documentVersion") String documentVersion);

    /** 有界查询已发布条款，不跨租户合并版本。 */
    List<Document> listDocuments(@Param("tenant") long tenant, @Param("after") long after, @Param("limit") int limit);
}
