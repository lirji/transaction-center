# 可信审批HTTP与拒绝审计

## 当前实现

默认关闭的专用审批HTTP已经接通，固定verify→accept，访问与回执使用独立RSA信任集合。真实RSA→HTTP→隔离MySQL用例已存在；历史240项全量与13项专项仅证明当时版本，当前验证以拒绝审计证据目录及CODEX_PROGRESS最新检查点为准。

## 拒绝审计合同

通过认证、专用scope和参数校验后，Ingress中的受控业务拒绝写入tc_operation_audit，动作固定refund.approval.rejected-request，资源类型APPROVAL_REQUEST。采用独立REQUIRES_NEW事务，审批业务回滚不会撤销该拒绝事实；它不代表人工批准或审批受理成功。

仅保存认证调用者哈希、固定分类与签名原文哈希，不存JWT原文、客户端任意分类或异常堆栈。持久化后保持原业务错误语义，响应traceId为ARJ资源编号，可在同租户审计中定位。审计提交失败或槽位繁忙返回503/REFUND_APPROVAL_AUDIT_UNAVAILABLE、retryable=true，审批仍不放行；重试保留原签名和来源事件号。指标故障不改变审计或拒绝结果。

每实例四槽位，tryAcquire不排队；Spring事务timeout为3秒，不是连接等待、网络和整个请求的硬截止，也不是全局限流。固定指标trade.refund.approval.rejection.audit仅使用outcome=recorded/failed/busy。生产仍需配置告警采集和网关配额。未认证、缺scope、参数错误、入口关闭及非TradeException基础设施失败不属于本拒绝审计覆盖范围。

## 外部合同

POST /api/v1/trade/integrations/refund-approvals，请求体只允许signedReceipt字符串（非空、最长16384字符），未知字段拒绝。访问JWT由现有签名/issuer/audience校验，必须有trade.refund.approval作用域；只有trade.admin或普通登录不能调用。

服务调用链固定为RefundApprovalVerifier.verify(已认证Actor,签名原文)→RefundApprovalReceiptService.accept(验证产物)。调用者不能提供内部VerifiedApprovalReceipt、人工Actor、租户或裸approve决定。Verifier进一步固定服务身份、回执签发方和专属接收方，回执中的人工身份源固定为交易访问令牌身份源。完整签名声明合同见REFUND_APPROVAL_RECEIPT.md。

200返回resourceId（RAP证据编号），代表审批及证据已经同步提交，不代表退款渠道已成功退款；相同来源事件重放返回同编号。400为报文或参数无效，401未认证，403缺专用scope或不可信回执，409来源事件/决定冲突，422业务关联/职责分离/期限不满足，503未启用或证据密钥/数据库不可用。绝不能把403/409自动换版本重试为新审批决定。业务已受理但客户端超时，必须按稳定来源事件合同恢复，不能人工改资金终态。

## 启用与回退

| 环境变量 | 含义 |
|---|---|
| TRADE_REFUND_APPROVAL_ENABLED | 默认false，只有完整来源合同验收后才显式开启 |
| TRADE_REFUND_APPROVAL_ISSUER | 固定流程回执签发方 |
| TRADE_REFUND_APPROVAL_JWKS | 固定HTTPS公钥地址，无userinfo或fragment，不接受请求动态指定 |
| TRADE_REFUND_APPROVAL_AUDIENCE | 专属审批接收方，禁止等于TRADE_JWT_AUDIENCE |
| TRADE_REFUND_APPROVAL_SERVICE_SUBJECT | 固定流程访问主体和回执服务subject |

缺省不装配Verifier，已通过专用scope的请求也返回503；开启但来源参数缺失或不合法则启动失败，不降级。现有证据密钥环仍必须配置并保留历史版本，不在Compose存放签名私钥。开关是启动配置，变更须受控重启，不是动态全局开关。关闭入口不撤销已受理审批，也不清除事件去重或暂停已批准退款恢复任务。

Compose把上述配置传给API，Worker固定关闭审批入口。仅执行config --quiet静态验证，未部署。生产网关仍需请求体上限、来源限流、TLS、最小权限与审计告警；JSON字段长度校验不等于网关流量防护。

## 验证范围与剩余门禁

RefundApprovalIngressTest检查关闭配置与MVC503、专用audience/HTTPS、verify→accept及审计失败关闭。RefundApprovalRejectionAuditTest检查独立事务定义、提交/回滚、四槽位立即拒绝和恢复、固定分类/摘要、指标故障。FoundationTest覆盖真实HTTP业务拒绝持久化、traceId关联、审计不可用503及无成功审批副作用，并证明外层回滚后审计仍存在。

FoundationTest#approvalHttpRealAccessAndReceiptSignaturesReachAtomicDatabaseAcceptance将访问JWT与回执验证委托真实Nimbus，独立RSA/JWKS密钥通过HTTP安全链到MySQL；覆盖伪签名、访问密钥冒签、跨租户、错误访问audience和合法重复。该测试使用本地JWKS，不代表生产HTTPS、真实流程身份配置或外部签署通过。

真实流程/渠道联调、KMS轮换、JWKS网络预算、边界审计与告警、容量/容灾、异常退款/退货/正式资格和完整裂变仍是必需门禁。本切片不新增DDL，不修改已执行V001～V017，不部署或修改其他平台。
