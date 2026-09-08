package com.lrj.trade.catalog.mapper.typehandler;

import java.sql.*;
import java.util.Map;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 规格JSON的持久化映射；业务校验仍由规格服务负责，读取结果保持不可变。 */
public class SpecificationTypeHandler extends BaseTypeHandler<Map<String,String>> {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    /** 通过绑定参数写入JSON，不能拼接用户规格值到SQL。 */
    @Override
    public void setNonNullParameter(PreparedStatement statement,int index,Map<String,String> value,JdbcType type) throws SQLException {
        statement.setString(index,JSON.writeValueAsString(value));
    }
    /** 列名读取保留规格对象，不将JSON文本直接暴露给接口。 */
    @Override
    public Map<String,String> getNullableResult(ResultSet result,String column) throws SQLException {
        return decode(result.getString(column));
    }
    /** 索引读取与列名读取保持相同的空值语义。 */
    @Override
    public Map<String,String> getNullableResult(ResultSet result,int column) throws SQLException {
        return decode(result.getString(column));
    }
    /** 存储过程输出采用同一规格对象映射规则。 */
    @Override
    public Map<String,String> getNullableResult(CallableStatement statement,int column) throws SQLException {
        return decode(statement.getString(column));
    }
    private Map<String,String> decode(String value) {
        return value==null?null:Map.copyOf(JSON.readValue(value,new TypeReference<Map<String,String>>() {}));
    }
}
