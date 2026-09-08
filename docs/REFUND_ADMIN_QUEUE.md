# 退款后台队列与可信审批接续

## 当前交付

新增GET /api/v1/trade/admin/refunds，沿用已验证JWT和trade.admin作用域；租户只能来自JWT，不能用查询参数覆盖。无新审批写接口、无状态修改、无自动批准。

参数：status默认PENDING_APPROVAL，允许READY/SUBMITTED/SUCCEEDED/FAILED/REJECTED/EXPIRED；after默认0，非负long；limit默认20，范围1～100。按id升序返回数组，最后一条cursor为下一页after，空数组结束。cursor输出字符串避免前端精度损失；前端不得先转Number再传回。状态发生变化时必须从0刷新，这不是快照分页，也不是可靠事件消费机制。

RefundQueueItem仅返回cursor/refundNo/orderNo/amount/currency/status/approvalRef/approvalDeadline/version，不含申请人身份、自由文本退款原因、渠道商户或密钥引用。默认日志脱敏。前端仍交Cursor，数据来自真实接口，禁止硬编码Mock。

持久化由RefundMapper.listQueue XML独占，固定tenant+status+id过滤匹配现有ix_refund_state索引；没有OFFSET、逐项明细查询或跨域表。Service只读短事务、参数白名单；DDL不变，共享仍V016。源码现为22Mapper/173SQL、44HTTP操作/34路径。

## 可验证条件

FoundationTest#refundAdminQueueIsBoundedTenantScopedAndReadOnly覆盖匿名401、会员403、异租户空集合、两页不重复、非法参数400、敏感字段不返回、审批后状态队列变化，以及查询前后资金/审批状态不变。原本人申请和策略权限测试一并回归。Mapper/Quality/OpenAPI门禁验证SQL隔离、注释和44接口合同一致。执行结果以最新进度和原报告为准。

## 为什么尚不开放批准接口

原设计FINAL_PLAN6.4与TC25要求拒绝伪造结果、旧版本重放及自审自批。现有trade.admin只说明后台权限，不能证明请求是流程平台签署的终态审批结果，也不能把服务账户身份伪装成人工审批人。因此本次不将RefundService.applyApproval直接绑定到HTTP。

下一切片须实现可信审批接收边界：固定来源系统/签发方/接收方与最小作用域、认证服务与真实审批人分离、tenant/refundNo/approvalRef/决定版本/决定/审批证据绑定、永久重复和冲突处理、审批到期及资金派发竞态、同事务唤醒与审计。流程真实配置缺失时保持待审批，不能假批准。先在交易内部落接收合同和隔离验证，再按既定顺序改造流程平台。

完整交易还缺异常多付补偿、实物退货、正式净支付资格事件等；后台列表不代替这些要求或完整裂变/生产三高验收。
