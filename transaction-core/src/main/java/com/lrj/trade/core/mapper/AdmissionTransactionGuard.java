package com.lrj.trade.core.mapper;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** 在持久化边界核对真实连接隔离级别，不依赖可能被外层事务覆盖的注解。 */
@Component
public class AdmissionTransactionGuard {
    private final DataSource source;
    /** 使用业务事务同一数据源，不创建第二个短事务。 */
    public AdmissionTransactionGuard(DataSource source) {this.source=source;}
    /** 缺失键共享锁的正确性要求整个受理事务采用RR。 */
    public void requireRepeatableRead() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("准入检查必须加入受理事务");
        Connection connection=DataSourceUtils.getConnection(source);
        try {
            if(connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ) throw new IllegalStateException("新增交易受理必须使用REPEATABLE_READ事务");
        } catch(SQLException error) {throw new IllegalStateException("无法核验受理事务隔离级别",error);}
        finally {DataSourceUtils.releaseConnection(connection,source);}
    }
}
