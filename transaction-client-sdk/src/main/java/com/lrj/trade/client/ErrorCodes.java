package com.lrj.trade.client;

/** 稳定业务错误常量；未来未知码仍通过 TradeFailure 字符串原样保留。 */
public final class ErrorCodes {
    private ErrorCodes() {}
    /** 管理查询限流，遵循Retry-After后重试原查询。 */
    public static final String ADMIN_QUERY_BUSY = "ADMIN_QUERY_BUSY";
    /** 管理查询存储暂不可用，由查询调用方决定重试，命令SDK默认停止。 */
    public static final String ADMIN_QUERY_UNAVAILABLE = "ADMIN_QUERY_UNAVAILABLE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    /** 订单归因授权边界错误，默认不自动重试。 */
    public static final String ORDER_ATTRIBUTION_UNTRUSTED = "ORDER_ATTRIBUTION_UNTRUSTED";
    /** 订单归因授权边界错误，默认不自动重试。 */
    public static final String ORDER_ATTRIBUTION_TRANSACTION = "ORDER_ATTRIBUTION_TRANSACTION";
    /** 订单归因授权边界错误，默认不自动重试。 */
    public static final String ORDER_ATTRIBUTION_DISABLED = "ORDER_ATTRIBUTION_DISABLED";
    /** 订单归因授权边界错误，默认不自动重试。 */
    public static final String ORDER_ATTRIBUTION_CONSUMED = "ORDER_ATTRIBUTION_CONSUMED";
    public static final String ADDRESS_ENCRYPTION_UNAVAILABLE = "ADDRESS_ENCRYPTION_UNAVAILABLE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String CALLBACK_BINDING_CONFLICT = "CALLBACK_BINDING_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String CALLBACK_ENCRYPTION_UNAVAILABLE = "CALLBACK_ENCRYPTION_UNAVAILABLE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String CALLBACK_EVIDENCE_INVALID = "CALLBACK_EVIDENCE_INVALID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String CALLBACK_FACT_CONFLICT = "CALLBACK_FACT_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String CATALOG_QUERY_BUSY = "CATALOG_QUERY_BUSY";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String CATALOG_QUERY_UNAVAILABLE = "CATALOG_QUERY_UNAVAILABLE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String CHANNEL_BINDING_CONFLICT = "CHANNEL_BINDING_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String CHANNEL_PROTOCOL_INVALID = "CHANNEL_PROTOCOL_INVALID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String DUPLICATE_RESOURCE = "DUPLICATE_RESOURCE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String EVIDENCE_DISABLED = "EVIDENCE_DISABLED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String EVIDENCE_UNAVAILABLE = "EVIDENCE_UNAVAILABLE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String EVIDENCE_UNTRUSTED = "EVIDENCE_UNTRUSTED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String FULFILLMENT_CONFLICT = "FULFILLMENT_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String FULFILLMENT_DISABLED = "FULFILLMENT_DISABLED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String FULFILLMENT_INVALID = "FULFILLMENT_INVALID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String FULFILLMENT_UNTRUSTED = "FULFILLMENT_UNTRUSTED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String HOT_SKU_BUSY = "HOT_SKU_BUSY";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String INVALID_IDENTITY = "INVALID_IDENTITY";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String JOB_LEASE_LOST = "JOB_LEASE_LOST";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String MEMBER_FROZEN = "MEMBER_FROZEN";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String MERCHANT_UNAVAILABLE = "MERCHANT_UNAVAILABLE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String ORDER_ADMISSION_PAUSED = "ORDER_ADMISSION_PAUSED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String ORDER_EXPIRED = "ORDER_EXPIRED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String OUTBOX_INVALID_EVENT = "OUTBOX_INVALID_EVENT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String OUTBOX_STATE_CONFLICT = "OUTBOX_STATE_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String PAYMENT_ADMISSION_PAUSED = "PAYMENT_ADMISSION_PAUSED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String PAYMENT_CHANNEL_BINDING_UNAVAILABLE = "PAYMENT_CHANNEL_BINDING_UNAVAILABLE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String PAYMENT_FACT_CONFLICT = "PAYMENT_FACT_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String PAYMENT_PERMISSION_CHANGED = "PAYMENT_PERMISSION_CHANGED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String PAYMENT_RESOLUTION_PENDING = "PAYMENT_RESOLUTION_PENDING";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String PAYMENT_UNRESOLVED = "PAYMENT_UNRESOLVED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String PRECONDITION_FAILED = "PRECONDITION_FAILED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String PRECONDITION_REQUIRED = "PRECONDITION_REQUIRED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String QUOTE_EXPIRED = "QUOTE_EXPIRED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RECONCILIATION_BINDING_CONFLICT = "RECONCILIATION_BINDING_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RECONCILIATION_CASE_CONFLICT = "RECONCILIATION_CASE_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RECONCILIATION_DISABLED = "RECONCILIATION_DISABLED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RECONCILIATION_OPERATION_INVALID = "RECONCILIATION_OPERATION_INVALID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RECONCILIATION_QUERY_UNAVAILABLE = "RECONCILIATION_QUERY_UNAVAILABLE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RECONCILIATION_UNTRUSTED = "RECONCILIATION_UNTRUSTED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_ALLOCATION_INVALID = "REFUND_ALLOCATION_INVALID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_AMOUNT_EXCEEDED = "REFUND_AMOUNT_EXCEEDED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_APPROVAL_AUDIT_UNAVAILABLE = "REFUND_APPROVAL_AUDIT_UNAVAILABLE";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_APPROVAL_CONFLICT = "REFUND_APPROVAL_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_APPROVAL_DISABLED = "REFUND_APPROVAL_DISABLED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_APPROVAL_EVENT_CONFLICT = "REFUND_APPROVAL_EVENT_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_APPROVAL_INVALID = "REFUND_APPROVAL_INVALID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_APPROVAL_UNTRUSTED = "REFUND_APPROVAL_UNTRUSTED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_BINDING_CONFLICT = "REFUND_BINDING_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_CALLBACK_BINDING_CONFLICT = "REFUND_CALLBACK_BINDING_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_CALLBACK_FACT_CONFLICT = "REFUND_CALLBACK_FACT_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_CHANNEL_BINDING_CONFLICT = "REFUND_CHANNEL_BINDING_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_CHANNEL_PROTOCOL_INVALID = "REFUND_CHANNEL_PROTOCOL_INVALID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_COMPENSATION_CONFLICT = "REFUND_COMPENSATION_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_COMPENSATION_INVALID = "REFUND_COMPENSATION_INVALID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_COMPONENT_EXCEEDED = "REFUND_COMPONENT_EXCEEDED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_FACT_CONFLICT = "REFUND_FACT_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_NOT_PAID = "REFUND_NOT_PAID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String REFUND_TERMINAL_CONFLICT = "REFUND_TERMINAL_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RETRY_SAME_KEY = "RETRY_SAME_KEY";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RETURN_CONFLICT = "RETURN_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RETURN_INVALID = "RETURN_INVALID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RISK_BINDING_CONFLICT = "RISK_BINDING_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RISK_DECISION_CONFLICT = "RISK_DECISION_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RISK_EXPIRED = "RISK_EXPIRED";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String RISK_PROTOCOL_INVALID = "RISK_PROTOCOL_INVALID";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String STATE_CONFLICT = "STATE_CONFLICT";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String TRANSACTION_BOUNDARY = "TRANSACTION_BOUNDARY";
    /** 服务端稳定错误标识，不依赖展示文案判断。 */
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    static final java.util.Set<String> RETRYABLE = java.util.Set.of(ADMIN_QUERY_BUSY,HOT_SKU_BUSY,CATALOG_QUERY_BUSY,ORDER_ADMISSION_PAUSED,PAYMENT_ADMISSION_PAUSED,REFUND_APPROVAL_AUDIT_UNAVAILABLE,EVIDENCE_UNAVAILABLE,RETRY_SAME_KEY);
}
