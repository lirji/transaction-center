# 管理端查询接口交付说明

2026-09-08；分支 `feat/admin-query-apis`。新增15个只读接口、增强2个已有接口，完整机器合同见 [OpenAPI](openapi.json)，设计与原始需求对照见 [设计方案](ADMIN_QUERY_API_PROPOSAL.md)。本文说明实际实现，不代表已经部署到本机8400或生产环境。

## 接口与页面接入

统一前缀 `/api/v1/trade/admin`。

| 方法／路径 | 用途及下钻 |
|---|---|
| GET /orders | 按 orderNo/memberId/status/financialStatus/warehouseId 搜单 |
| GET /orders/{orderNo} | 冻结商品、returnedQuantity、履约摘要、独立财务状态 |
| GET /payments | 按 paymentNo/orderNo/memberId/status 查支付意图 |
| GET /payments/{paymentNo} | 当前尝试、正常成功收款编号及关闭屏障 |
| GET /payments/{paymentNo}/attempts | 单意图的全部尝试，支持status |
| GET /payment-receipts | 按 receiptNo/paymentNo/orderNo/channelCode/disposition/abnormalOnly 查实收 |
| GET /payment-receipts/{receiptNo} | 实收、累计退款、预占及未占用金额 |
| GET /refunds/{refundNo} | 退款分项、审批引用、版本、processingReasonCode |
| GET /returns | 按 returnNo/orderNo/memberId/orderItemId/warehouseId/status 查退货申请 |
| GET /quotes | 按 quoteId/memberId 查不可变报价 |
| GET /quotes/{quoteId} | 报价冻结商品与失效时间 |
| GET /orders/{orderNo}/referral-evidence | 当前可信交易证据，不能作为营销资格预占 |
| POST /members/{memberId}/eligibility:query | 交易原生新客策略只读评估 |
| GET /jobs | 按 jobType/status/leaseExpired 查任务 |
| GET /jobs/{jobId} | 租约观察与安全业务引用 |
| GET /refunds（增强） | 保留原数组、cursor和默认待审批；新增refundNo/orderNo/memberId及时间筛选，显式status=ALL查全部 |
| GET /returns/{returnNo}（增强） | 保留原字段和ETag；新增时间及inspection数量摘要 |

新增列表返回 `{items,nextAfter,hasMore,observedAt}`。`after`默认0、`limit`默认20最大100，按根记录ID升序；不能把升序当“最新优先”。改变筛选时清空游标。除单支付尝试列表外，可使用成对的 `createdFrom/createdTo`，UTC毫秒精度、左闭右开、最大90天。精确编号与时间条件也取AND。未知或重复参数返回400。

所有业务ID为字符串，金额／数量／版本为JS安全整数。内部cursor不出现在新增列表行内；翻页读取Page.nextAfter。列表也携带若干详情的安全标量字段，具体以OpenAPI为准，页面不需要额外调用详情来获得这些字段。

退款原数组接口继续以末条 `cursor` 翻页，不改成 Page。退货验收继续使用详情响应ETag作为If-Match；不使用inspection的实收编号代替RMA申请编号。

## 实际权限矩阵

| 授权角色 | JWT必须包含 | 允许读取 |
|---|---|---|
| 租户交易管理员 | trade.admin | 本租户订单、支付／收款、退款、退货、报价全部安全投影 |
| 证据诊断管理员 | trade.admin + trade.admin.evidence.read | 上述普通查询及证据、资格评估 |
| 任务诊断管理员 | trade.admin + trade.admin.jobs.read | 上述普通查询及任务、租约 |
| 普通会员 | 无管理scope | 不能调用这些管理查询，仍使用本人接口 |

`trade.admin`在当前应用是全权租户管理角色，不是客服只读角色，也不是跨租户平台角色。此版本保持该既有授权语义；如果要区分仅订单客服／仅财务角色，仍需单独的IAM角色和资源scope改造，不能给这些角色发trade.admin再只隐藏前端按钮。没有改造或配置现有IAM。

证据／任务路径包括HEAD都执行额外scope校验。证据还需显式设置 `TRADE_ADMIN_EVIDENCE_ENABLED=true`，默认关闭；内部EvidenceAccess的开关、服务身份允许列表和scope保持独立。原退款审批仍仅接收可信签名回执，trade.admin不能代替审批服务。

## 查询语义与保护

- 主库只读RR事务、3秒语句／事务超时；不调用支付渠道、不领取任务、不自动修复数据。3秒不是P99性能承诺或HTTP硬截止。
- 默认每API实例最多4个并发管理读取、每租户最多2个并发、20次／秒；配置分别为 `TRADE_ADMIN_QUERY_CONCURRENCY`、`TRADE_ADMIN_QUERY_TENANT_CONCURRENCY`、`TRADE_ADMIN_QUERY_TENANT_RATE`。租户计数器最多1024个，闲置项回收。部署多实例需要网关汇总配额。
- 配额拒绝429，`Retry-After: 1`；数据库暂时不可用503／ADMIN_QUERY_UNAVAILABLE；永久SQL／映射缺陷按500处理，不伪装为可自动恢复。响应 `Cache-Control: no-store`。
- `trade.admin.query.audit`结构化日志记录租户、主体摘要、HTTP方法及资源路径摘要、HTTP结果与traceId；包括配额拒绝。不记录请求体、原始证据、任务键或身份令牌。安全过滤链在进入查询前的401/403沿用安全层审计，不能宣称由此日志完整覆盖。
- 跨页不保证固定快照。详情内的主记录和子记录一致读取；聚合读取不生成仅基于订单version的ETag。

## 相对草案的确定落点

1. 所有新根投影都返回version，并保护JSON整数上界；单项版本不代表多个领域的统一聚合版本。
2. `processingReasonCode`来自同快照 `REFUND_RECOVER` 任务的持久last_error，仅取READY/RUNNING/REVIEW状态且符合分类码格式；没有可靠来源时null。它是本地处理分类，不是伪造的渠道失败原因。用户自由文本申请原因不返回。
3. jobs详情支持ORDER_RESOLVE、PAYMENT_RECOVER、REFUND_RECOVER、REFUND_COMPENSATE、OUTBOX_PUBLISH、RECON_QUERY六类业务引用；仅在同租户目标确实存在时返回。回调型任务及坏键返回null，不回显内部键。
4. 退货状态仅OPEN/INSPECTED；inspection中的recordedAt是实收事实本地登记时间，不是实际到货时间；不从数量猜造验收结果枚举。
5. 普通查询维持trade.admin兼容授权，诊断权限单独隔离。新增接口没有修改退款审批、会员本人归属和内部服务身份边界。

## 数据库与验证

新增 `V033__admin_query_indexes.sql`，只追加20个管理查询索引，不建业务表、不改历史迁移或共享数据。已有tenant/jobType/status/id索引复用，未重复创建。

后续部署前通过正常Flyway流程应用V033；本次仅在自动化隔离MySQL中执行，不对当前8400演示库执行迁移或重启。

复现命令：

```bash
# 本机使用已缓存的MySQL 8.4.11；禁用缺失的Ryuk镜像，JUnit负责关闭本测试创建的容器。
TESTCONTAINERS_RYUK_DISABLED=true mvn -Dtrade.test.mysql-image=mysql:8.4.11 verify
python3 scripts/generate-admin-query-openapi.py
```

FoundationTest覆盖实际HTTP、MySQL投影、租户隔离、会员归属、分页、异常收款、退款处理分类、退货ETag及验收数量、证据／资格／HEAD权限。AdminQueryTest覆盖参数、整数、配额与默认关闭。OpenApiTest核对81个已实现HTTP操作。

索引专项用2000条隔离任务记录验证选择性状态查询的实际Mapper EXPLAIN，确认使用租户状态索引，并检查过期租约读取不触发接管。这是局部执行计划验证，不代表原方案全量容量、极端组合筛选或三高SLO已验收。

真实渠道、IAM授权配置、网关集群配额、生产容量验收仍需相应部署／联调。本次前端由Cursor继续按上述合同接入，后端源码完成不代表现有运行容器已提供新接口。

实际验证结果：完整 `mvn verify` 退出0，450项测试，0失败／错误／跳过，其中FoundationTest 316项、AdminQueryTest 6项。独立只读审查发现的HEAD权限和429审计问题已修复并复核。报告见 [verification.json](delivery/admin-query-apis/verification.json)，文件清单见 [FILES.txt](delivery/admin-query-apis/FILES.txt)。
