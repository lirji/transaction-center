# 可信退款审批回执合同（认证前置，尚未启用）

## 权限与调用链

后续接收链为：访问令牌验签及专用scope → 固定服务issuer/subject → 独立签名审批回执验真 → 加密Inbox永久事件去重/冲突 → 本地关联与职责分离校验 → 审批/审计/持久唤醒原子提交。当前只实现RefundApprovalVerifier与VerifiedApprovalReceipt，不是完整链路，没有Bean、HTTP或资金调用。

通用trade.admin不允许代替可信流程身份。调用者Actor必须先由已验证访问令牌构造；verifier绑定其issuer/subject/tenant，不自行验证访问令牌或授予scope。真实审批人由可信流程签名声明，issuer必须匹配配置的身份源，不能直接填服务主体代替人；最终由RefundApprovalPort检查申请人与审批人不同。

## 签名合同

紧凑JWS最长16384字符，通过固定配置的JWKS验证；不读取报文中动态地址。回执签发方与audience独立固定，不能复用普通交易访问令牌的audience。当前Nimbus默认RSA签名验证，实际密钥托管、轮换、失败预算及供应商联调仍需部署认证，不能仅由此类宣称完成。

| 签名声明 | 约束 |
|---|---|
| iss / aud / sub | 固定回执签发方、专用接收方、固定流程服务主体 |
| receipt_kind / schema_version | REFUND_APPROVAL_DECISION_V1 / 整数1 |
| tenant_id | 十进制字符串，严格等于访问令牌所属租户 |
| jti | 非空，最长64，未来作为来源+租户永久事件去重键的一部分 |
| refund_no / approval_ref | 非空，分别最长64，必须由资金受理层核对原申请 |
| approver_issuer / approver_subject | 原身份源与人工主体，分别最长255，不接受服务冒充人 |
| decision / decision_version | APPROVE或REJECT；正整数且不超过9007199254740991，不接受浮点或字符串版本 |
| evidence_ref | 非空，最长255，流程证据引用 |
| iat / exp / nbf | 必须有iat/exp；有效区间大于0且不超过5分钟，当前时间必须严格早于exp；iat最多领先30秒，nbf按30秒容差检查 |

JWT校验与业务窗口共用同一Clock，防默认系统时钟与注入时间不一致。错误统一REFUND_APPROVAL_UNTRUSTED，无原始异常cause或JWT原文。VerifiedApprovalReceipt保留验签来源、人工Actor和完整签名原文用于后续加密留证，toString完全脱敏；不得作为HTTP响应直接序列化。

## 下一持久受理切片必须完成

V017仅新增审批Inbox，不重写V001～V016；表/字段全部中文注释。明确来源事件唯一键、语义冲突及历史重签规则；签名原文认证加密，缺密钥不得确认接收。仅验签成功但未提交数据库不能返回业务受理成功。审批关联、版本、自审自批、24小时本地期限及派发终态仍由退款域在同一事务重检，不能用JWT有效期替代业务审批期限。

处理失败、重试、重复回执、反向决定、来源或租户冲突、加密不可用、持久化回滚、审批到期竞争都需真实数据库验收。访问令牌scope、固定服务配置和运行开关缺省拒绝，正式签署前不开放。流程平台改造仍排在交易内部完成之后。

## 验证范围

RefundApprovalVerifierTest使用真实RSA与本地JWKS，覆盖伪签名、未知kid、unsigned、签发方/接收方/服务/租户、人工身份、版本类型、安全整数、必填字段、精确时间边界及远离系统日期的固定Clock；JwtValidationTest另覆盖原访问令牌验签。独立审查发现时钟不一致后已修复。最新命令结果见CODEX_PROGRESS及原报告；这些认证测试不等于持久受理/HTTP或流程平台验收。
