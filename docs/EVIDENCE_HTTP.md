# 可信原生证据HTTP入口

本切片接线四个交易原生只读操作：

| 方法/路径 | 输入 | 输出 |
|---|---|---|
| GET `/internal/v1/orders/{orderId}/referral-evidence` | 原生订单ID | OrderReferralEvidence；不存在/其他租户404 |
| POST `/internal/v1/order-referral-evidence:query` | orderIds（1～100个字符串ID） | 保序逐项FOUND/NOT_FOUND，未找到项evidence=null |
| POST `/internal/v1/first-order-referral-evidence:query` | memberId、from、to、firstOrderPolicyVersion | 全局永久首单按本地确认时间筛选[from,to)，READY携带firstConfirmedAt及当前金额证据；否则NOT_FOUND |
| POST `/internal/v1/customer-eligibility:query` | memberId、policyVersion、可选campaignStartAt/evaluatedAt | READY及原生会员策略证据，或NOT_FOUND且evidence=null |

字段和类型见openapi.json；ID为正十进制字符串，最大9223372036854775807。只支持两个版本化会员策略，活动注册策略必须提供campaignStartAt。evaluatedAt省略表示当前主库快照，显式非未来时点仅做既定不可变事实策略投影。金额查询只返回当前完整快照。

这是交易原生合同。原裂变C02/C03同路径中的canonicalSubject、organizationId/shopId权威映射尚未接入；原生memberId首单期间补查已独立实现；这类请求形状当前400，不能伪造READY，也不能将只实现orderIds批量当作完成首单补查。后续adapter须保留两种能力并完成双方签收，详见REFERRAL_EVIDENCE_V1_DESIGN。

## 身份与配置

入口默认关闭。启用必须同时配置：

- `TRADE_EVIDENCE_ENABLED=true`
- `TRADE_EVIDENCE_ALLOWED_ACTOR_KEYS`：一个或多个64位小写十六进制服务身份摘要，以逗号分隔。启用且空列表、无效摘要使启动失败。
- 调用令牌通过既有JWT签名、issuer、audience、时间验证，并含`trade.evidence.read`作用域及有效tenant_id。普通会员/admin作用域不具备读取权限。

每个摘要等于`Checks.hash(issuer, subject)`：依序将每个字段编码UTF-8，对字节长度十进制加冒号及原字节串执行SHA-256，输出小写hex；不是直接拼接issuer/subject，也不是令牌摘要。服务身份允许列表以issuer+subject共同绑定。服务可用的租户由身份提供方签发的tenant_id授权，服务端不接收请求体或额外头覆盖租户；生产签署须明确允许服务的租户范围与令牌发放策略。

关闭返回503/EVIDENCE_DISABLED，已认证但未允许的服务403/EVIDENCE_UNTRUSTED，无有效租户403/INVALID_IDENTITY；缺认证401、缺scope403由安全过滤链拒绝。证据不完整503/EVIDENCE_UNAVAILABLE且retryable=true；不返回SQL、渠道交易号或凭据。JSON未知字段拒绝，不能自报租户/金额/首单。安全过滤链的拒绝可能没有业务Failure响应体，领域拒绝使用统一Failure。

Compose显式传递API开关与列表；worker固定关闭证据入口。没有配置任何真实服务身份，也未部署或开放生产流量。

## 测试与边界

完整过滤链测试覆盖：匿名、允许身份缺scope/仅admin、专用scope但陌生身份、非法tenant、合法读取、跨租户单查/批量、101条/空元素/ID溢出、未支持canonicalSubject形状、请求自报租户。默认关闭另用包含完整SecurityFilterChain的WebApplicationContext验证503且不读事实；配置空/错列表及issuer/subject摘要隔离有单元测试。

JWT解码在HTTP集成用例中使用受控测试替身，过滤链和数据库查询是真实实现；既有JWT签名验证组件不在本切片重写。生产服务令牌发放/权限与跨平台签署仍待联调，不以本地测试替代。
