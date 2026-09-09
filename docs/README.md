# 交易中心文档导航

[当前交付状态](PROJECT_STATUS.md) · [管理查询接口](ADMIN_QUERY_API.md) · [控制台交接](CURSOR_HANDOFF.md)

本目录面向产品、后端开发、测试和运维，描述当前仓库中的交易能力。更新日期：2026-09-09；代码基线：已推送main的 `3187c57`。先阅读 [当前交付状态](PROJECT_STATUS.md)，区分已实现、已在本机部署与待生产验收的范围。

## 推荐阅读顺序

| 文档 | 解决的问题 | 主要读者 |
| --- | --- | --- |
| [项目架构](PROJECT_ARCHITECTURE.md) | 系统由哪些模块构成，如何协作和部署 | 开发、架构、运维 |
| [业务说明](BUSINESS_GUIDE.md) | 交易主流程、状态机、业务规则和外部边界 | 产品、开发、测试 |
| [高并发与一致性设计](HIGH_CONCURRENCY_DESIGN.md) | 如何防重复、防超卖、恢复未知结果及控制负载 | 开发、架构、测试 |
| [数据架构](DATA_ARCHITECTURE.md) | 数据归属、核心关系、约束、索引及迁移 | 开发、数据库维护人员 |
| [部署运维与容量验证](OPERATIONS_AND_CAPACITY.md) | 如何运行、观测、恢复以及验证容量 | 运维、测试、开发 |

先读架构和业务，再结合并发、数据与运维文档阅读代码。接口字段、路径与响应以 [OpenAPI](openapi.json) 和对应 Controller 为依据；事件以 [AsyncAPI](asyncapi.json)、[事件合同](ASYNCAPI_CONTRACT.md) 和 [contracts](contracts/) 为依据。当前 OpenAPI 文件包含 **69 个路径、81 个 HTTP 操作**，这是合同文件统计，不是生产可用接口数量；部分接口需要开关和来源授权。

## 能力状态

| 范围 | 当前源码能力 | 仍需完成或外部验证 |
| --- | --- | --- |
| 基础交易 | 会员、商品 SKU、报价、库存预占、订单、零元路径 | 业务环境与前端完整验收 |
| 资金 | 支付/退款本地状态机、额度、密文 Inbox、恢复及补偿任务 | 真实渠道、风控提供方、原始通知验签适配及生产联调 |
| 履约售后 | 整单交付、独立商业完成、退货申请/验收与唯一回库 HTTP/服务 | WMS/服务交付证据核验及生产来源授权；入口默认关闭 |
| 事件与调度 | 八类 Worker、租户轮转、七类 V1 事件发布、受控重放和维护 | 生产 Kafka 配置、消费者去重/乱序处理与签收 |
| 并发保护 | 热点 SKU、本地单飞缓存回源、人工/自动准入 | 指定数据规模的压力与故障测试、数据库磁盘映射 |
| 营销归因 | 签名令牌与永久订单快照、交易事实及映射端口 | 权威主体映射默认 Pending；完整资格、发奖、追回不由本仓库单独完成 |
| 对账 | 资金事实查询、差异受理、只查单操作、观测 | 外部账单匹配、结案及最终财务核对 |

“代码已实现”“功能测试曾通过”“本机部署完成”“生产验收通过”是四种不同结论。本次只做文档整理与静态核对，不重新认证历史测试结果。

## 专题索引

| 主题 | 详细合同与操作说明 |
| --- | --- |
| 管理查询与控制台 | [查询合同](ADMIN_QUERY_API.md)、[控制台交接](CURSOR_HANDOFF.md) |
| 会员与资格 | [会员生命周期](MEMBER_LIFECYCLE.md)、[消费事实](MEMBER_TRADE_FACTS.md)、[资格查询](CUSTOMER_ELIGIBILITY.md) |
| 商品报价与库存 | [SKU 与报价](SKU_AND_QUOTE.md)、[缓存和热点 SKU](CATALOG_CACHE_AND_HOT_SKU_DESIGN.md)、[库存 V1](INVENTORY_V1_PUBLISHING_DESIGN.md) |
| 订单 | [订单基础](ORDER_FOUNDATION.md)、[取消/过期恢复](ORDER_RESOLUTION_PLAN.md)、[订单归因](ORDER_REFERRAL_ATTRIBUTION_DESIGN.md) |
| 支付 | [支付核心](PAYMENT_CORE.md)、[渠道绑定](PAYMENT_CHANNEL_BINDING.md)、[回调 Inbox](PAYMENT_CALLBACK_INBOX.md)、[恢复 Worker](PAYMENT_RECOVERY_WORKER.md) |
| 退款 | [申请与额度](REFUND_APPLICATION.md)、[审批 HTTP](REFUND_APPROVAL_HTTP.md)、[退款回调](REFUND_CALLBACK_INBOX.md)、[恢复](REFUND_RECOVERY_WORKER.md)、[异常补偿](REFUND_COMPENSATION.md) |
| 履约与退货 | [履约核心](ORDER_FULFILLMENT.md)、[退货核心](RETURN_RECEIPTS.md)、[授权入口](FULFILLMENT_ACCESS_DESIGN.md)、[操作说明](FULFILLMENT_OPERATIONS.md) |
| 事件与任务 | [任务租约](JOB_LEASES.md)、[常驻 Worker](WORKER_RUNTIME_PLAN.md)、[可靠发布](OUTBOX_PUBLISHING.md)、[受控操作](OUTBOX_OPERATIONS.md)、[维护](OUTBOX_MAINTENANCE_OPERATIONS.md) |
| 对账观测 | [对账操作](RECONCILIATION_OPERATIONS.md)、[财务观测](FINANCIAL_OBSERVATION.md)、[追踪上下文](TRACE_CONTEXT_DESIGN.md) |
| 安全与接入 | [Casdoor](CASDOOR_LOCAL_SETUP.md)、[证据 HTTP](EVIDENCE_HTTP.md)、[管理版本合同](ADMIN_VERSION_CONTRACT.md)、[错误 SDK](ERROR_SDK_DESIGN.md) |
| 测试与交付 | [验证记录](VERIFICATION.md)、[覆盖账本](ACCEPTANCE_COVERAGE.md)、[Git 交付](GIT_DELIVERY.md)、[Cursor 交接](CURSOR_HANDOFF.md) |

## 文档维护规则

早期专题中的迁移版本、接口数量、测试数量及“后续待实现”通常对应当时切片。已在易混淆文档顶部补充时效提示，历史正文用于追溯；阅读当前能力时先看本导航和总览，再追到对应实现。`DESIGN` 文件也可能包含后续实际实现记录，文件名本身不能证明完成状态。

新增业务时同步状态机、事务/锁顺序、数据约束、API/事件合同和验证证据。容量数字必须附环境、数据规模、负载模型和原始结果。不要把本机凭据、JWT 或回调原文复制到文档中。
