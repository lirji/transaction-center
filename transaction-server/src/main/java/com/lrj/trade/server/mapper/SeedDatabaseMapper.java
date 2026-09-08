package com.lrj.trade.server.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** DevSeed的持久化边界；SQL仅位于同名XML，事务由业务层统一编排。 */
@Mapper
public interface SeedDatabaseMapper {
    /** 读取连接实际数据库名称，开发种子必须先通过隔离校验。 */
    String currentDatabase();
}
