package com.lrj.trade.server;

import com.lrj.trade.core.TradeException;
import java.util.UUID;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 统一错误映射，不向调用方泄露SQL、内部表名或凭据。 */
@RestControllerAdvice
public class Errors {
    /** 拒绝审计已独立提交，响应关联号可查询审计，不回显未验真报文。 */
    @ExceptionHandler(ApprovalRejection.class)
    public ResponseEntity<Failure> approvalRejected(ApprovalRejection error) {
        int status=statusFor(error.code());
        return ResponseEntity.status(status).body(new Failure(error.code(),error.getMessage(),error.auditId(),status==503));
    }
    /** 稳定业务码区分参数错误、并发冲突和资源不存在。 */
    @ExceptionHandler(TradeException.class)
    public ResponseEntity<Failure> business(TradeException error) {
        int status=statusFor(error.code());
        if(java.util.Set.of("HOT_SKU_BUSY","CATALOG_QUERY_BUSY","ADMIN_QUERY_BUSY").contains(error.code())) return ResponseEntity.status(429).header("Retry-After","1").body(new Failure(error.code(),error.getMessage(),com.lrj.trade.core.TraceContext.correlationUuid(),true));
        if(error.code().equals("ORDER_ADMISSION_PAUSED") || error.code().equals("PAYMENT_ADMISSION_PAUSED"))
            return ResponseEntity.status(503).header("Retry-After","5").body(new Failure(error.code(),error.getMessage(),com.lrj.trade.core.TraceContext.correlationUuid(),true));
        return response(status,error.code(),error.getMessage(),java.util.Set.of("REFUND_APPROVAL_AUDIT_UNAVAILABLE","EVIDENCE_UNAVAILABLE","ADMIN_QUERY_UNAVAILABLE",
            com.lrj.trade.server.checkout.CheckoutPilotQuoteService.UNAVAILABLE).contains(error.code()));
    }
    // 审计包装仅增加关联号，不能改变原业务码的HTTP分类。
    private int statusFor(String code) {
        return switch(code) {
            case "ORDER_ATTRIBUTION_DISABLED","ADMIN_QUERY_UNAVAILABLE" -> 503;
            case "ORDER_ATTRIBUTION_UNTRUSTED" -> 403;
            case com.lrj.trade.server.checkout.CheckoutPilotQuoteService.UNTRUSTED -> 403;
            case com.lrj.trade.server.checkout.CheckoutPilotQuoteService.CONFLICT -> 409;
            case com.lrj.trade.order.OrderService.CHECKOUT_FUNDING_PENDING -> 409;
            case com.lrj.trade.server.checkout.CheckoutPilotQuoteService.UNAVAILABLE -> 503;
            case "ORDER_ATTRIBUTION_TRANSACTION","ORDER_ATTRIBUTION_CONSUMED" -> 409;
            case "NOT_FOUND" -> 404;
            case "HOT_SKU_BUSY","CATALOG_QUERY_BUSY","ADMIN_QUERY_BUSY" -> 429;
            case "FULFILLMENT_DISABLED","CATALOG_QUERY_UNAVAILABLE" -> 503;
            case "FULFILLMENT_UNTRUSTED" -> 403;
            case "PRECONDITION_FAILED" -> 412;
            case "PRECONDITION_REQUIRED" -> 428;
            case "ORDER_ADMISSION_PAUSED","PAYMENT_ADMISSION_PAUSED" -> 503;
            case "INVALID_ARGUMENT" -> 400;
            case "INVALID_IDENTITY" -> 403;
            case "RECONCILIATION_UNTRUSTED","REFUND_APPROVAL_UNTRUSTED","EVIDENCE_UNTRUSTED" -> 403;
            case "REFUND_APPROVAL_DISABLED","CALLBACK_ENCRYPTION_UNAVAILABLE","REFUND_APPROVAL_AUDIT_UNAVAILABLE" -> 503;
            case "RECONCILIATION_CASE_CONFLICT","RECONCILIATION_BINDING_CONFLICT","RETURN_CONFLICT","FULFILLMENT_CONFLICT","REFUND_COMPENSATION_CONFLICT","REFUND_APPROVAL_EVENT_CONFLICT","REFUND_APPROVAL_CONFLICT" -> 409;
            case "RECONCILIATION_DISABLED","ADDRESS_ENCRYPTION_UNAVAILABLE","EVIDENCE_UNAVAILABLE","EVIDENCE_DISABLED" -> 503;
            case "IDEMPOTENCY_CONFLICT","VERSION_CONFLICT","STATE_CONFLICT","PAYMENT_UNRESOLVED","PAYMENT_FACT_CONFLICT" -> 409;
            default -> 422;
        };
    }
    /** 重复业务编号不是服务故障，也不能覆盖原资源。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Failure> duplicate() { return response(409,"DUPLICATE_RESOURCE","业务编号已存在",false); }
    /** 锁冲突可使用原幂等键重试，不建议创建新的业务请求号。 */
    @ExceptionHandler(TransientDataAccessException.class)
    public ResponseEntity<Failure> transientFailure() { return response(503,"RETRY_SAME_KEY","暂时不可用，请保留原幂等键重试",true); }
    /** 对无效JSON、缺少请求头和路径类型错误统一返回400。 */
    @ExceptionHandler({HttpMessageNotReadableException.class,ServletRequestBindingException.class,MethodArgumentTypeMismatchException.class,MethodArgumentNotValidException.class})
    public ResponseEntity<Failure> malformed() { return response(400,"INVALID_ARGUMENT","请求格式或参数无效",false); }
    /** 未分类异常保持失败关闭，不把不确定结果伪装成功。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> unexpected(Exception error) {
        String trace=com.lrj.trade.core.TraceContext.correlationUuid();
        org.slf4j.LoggerFactory.getLogger(Errors.class).error("交易请求失败 traceId={} type={}",trace,error.getClass().getName());
        return ResponseEntity.status(500).body(new Failure("INTERNAL_ERROR","内部处理失败",trace,false));
    }
    private ResponseEntity<Failure> response(int status,String code,String message,boolean retryable) {
        return ResponseEntity.status(status).body(new Failure(code,message,com.lrj.trade.core.TraceContext.correlationUuid(),retryable));
    }
    /** 脱敏错误响应；普通请求关联追踪UUID，审批拒绝保留独立审计号。 */
    public record Failure(String code,String message,String traceId,boolean retryable) {}
}
