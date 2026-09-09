package com.lrj.trade.server;
import com.lrj.trade.core.TraceContext;
import org.springframework.core.annotation.Order;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/** 查询数据库故障不要求命令幂等键，也不将异常报文暴露给管理员。 */
@Order(-10)
@RestControllerAdvice(assignableTypes=AdminQueryController.class)
public class AdminQueryErrors {
    /** 读取失败可重试原查询，不触发补偿或领域写入。 */
    @ExceptionHandler({TransientDataAccessException.class,DataAccessResourceFailureException.class,TransactionTimedOutException.class,CannotCreateTransactionException.class})
    public ResponseEntity<Errors.Failure> unavailable(Exception error) {
        return ResponseEntity.status(503).body(new Errors.Failure("ADMIN_QUERY_UNAVAILABLE","查询暂时不可用",TraceContext.correlationUuid(),true));
    }
}
