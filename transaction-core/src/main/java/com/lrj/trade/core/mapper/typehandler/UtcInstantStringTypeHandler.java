package com.lrj.trade.core.mapper.typehandler;

import java.sql.*;
import java.time.Instant;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 仅供XML显式引用，保持原JDBC时间输出契约，不能全局覆盖普通字符串映射。 */
public class UtcInstantStringTypeHandler extends BaseTypeHandler<String> {
    /** 时间参数按同一瞬间绑定，不依赖数据库字符串格式化规则。 */
    @Override
    public void setNonNullParameter(PreparedStatement statement,int index,String value,JdbcType type) throws SQLException {
        statement.setTimestamp(index,Timestamp.from(Instant.parse(value)));
    }
    /** 保留原getTimestamp到Instant的UTC输出，避免重构后出现本地时间字符串。 */
    @Override
    public String getNullableResult(ResultSet result,String column) throws SQLException {
        return format(result.getTimestamp(column));
    }
    /** 索引读取与列名读取采用相同的空值和时区语义。 */
    @Override
    public String getNullableResult(ResultSet result,int column) throws SQLException {
        return format(result.getTimestamp(column));
    }
    /** 存储过程输出同样使用UTC表示，不引入第二种时间契约。 */
    @Override
    public String getNullableResult(CallableStatement statement,int column) throws SQLException {
        return format(statement.getTimestamp(column));
    }
    private String format(Timestamp value) { return value==null?null:value.toInstant().toString(); }
}
