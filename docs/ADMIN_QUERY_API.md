# 管理端查询接口交付说明

> 状态更新（2026-09-09）：本合同已实现并推送main；V033已应用到演示库，前后端已本机部署。SDK错误码补齐后的验证范围见 [当前交付状态](PROJECT_STATUS.md)。第“验证”部分的历史450项记录保留原范围。

2026-09-08；分支 `feat/admin-query-apis`。新增15个只读接口、增强2个已有接口，完整机器合同见 [OpenAPI](openapi.json)，设计与原始需求对照见 [设计方案](ADMIN_QUERY_API_PROPOSAL.md)。本文说明实际实现，不代表已经部署到本机8400或生产环境。

## 接口与页面接入

统一前缀 `/api/v1/trade/admin`。

| 方法／路径 | 用途及下钻 |
|---|---|
| GET /orders | 按 orderNo/memberId/status/financialStatus/warehouseId 搜单，支持完整号、原号及 `YYMMDD-尾号四位` |
| GET /order-pages | 按页码查本租户订单，返回准确总记录数、总页数和本页数据 |
| GET /orders/{orderNo} | 冻结商品、returnedQuantity、履约摘要、独立财务状态 |
| GET /payments | 按 paymentNo/orderNo/memberId/status 查支付意图 |
| GET /payments/{paymentNo} | 当前尝试、正常成功收款编号及关闭屏障 |
| GET /payments/{paymentNo}/attempts | 单意图的全部尝试，支持status |
| GET /payment-receipts | 按 receiptNo/paymentNo/orderNo/channelCode/disposition/abnormalOnly 查实收 |
| GET /payment-receipts/{receiptNo} | 实收、累计退款、预占及未占用金额 |
| GET /refunds/{refundNo} | 退款分项、审批引用、版本、processingReasonCode |
| GET /orders/{orderNo}/operation-evidence | 订单及实际收退款记录，独立分页，试点返回已冻结商城报价关联；另需 trade.admin.evidence.read |
| GET /operation-audits | 操作日志列表，跨业务查询当前租户已留痕操作，支持对象和记录时间筛选 |
| GET /refunds/{refundNo}/operation-audits | 当前租户退款的申请、审批、已核实渠道终态、重复请求及业务效果证据 |
| GET /returns | 按 returnNo/orderNo/memberId/orderItemId/warehouseId/status 查退货申请 |
| GET /quotes | 按 quoteId/memberId 查不可变报价 |
| GET /quotes/{quoteId} | 报价冻结商品与失效时间 |
| GET /orders/{orderNo}/referral-evidence | 当前可信交易证据，不能作为营销资格预占 |
| POST /members/{memberId}/eligibility:query | 交易原生新客策略只读评估 |
| GET /jobs | 按 jobType/status/leaseExpired 查任务 |
| GET /jobs/{jobId} | 租约观察与安全业务引用 |
| GET /refunds（增强） | 保留原数组、cursor和默认待审批；新增refundNo/orderNo/memberId及时间筛选，显式status=ALL查全部 |
| GET /returns/{returnNo}（增强） | 保留原字段和ETag；新增时间及inspection数量摘要 |

通用管理列表返回 `{items,nextAfter,hasMore,observedAt}`。`after`默认0、`limit`默认20最大100，按根记录ID升序；不能把升序当“最新优先”。改变筛选时清空游标。除单支付尝试列表外，可使用成对的 `createdFrom/createdTo`，UTC毫秒精度、左闭右开、最大90天。精确编号与时间条件也取AND。未知或重复参数返回400。

订单页码接口 `GET /order-pages` 复用原查询条件与排序。可传 `page`（默认 1）和 `limit`（默认 20，范围 1–100），拒绝 `after`。响应为 `{items,page,pageSize,totalCount,totalPages,observedAt}`。空结果返回页码 1、总页数 0；超出末页时返回最后一页及其实际页码。总量和订单行在同一个数据库快照中读取。页面切换会重新读取最新快照，并发新增订单或状态变化可能改变页界，原游标接口与已保存的游标链接继续有效。

编号分页只为管理台明确发起的查询执行计数。查询选择页内键后再读取展示字段，沿用现有租户索引、配额和查询超时。真实全量计数和深页成本随筛选范围增长，当前验收覆盖正确性，生产级分页容量和跨请求固定快照仍待独立评估。异常返回既有管理查询错误，客户端可缩小范围后重试。

全局操作日志使用 `{items,nextCursor}`。结果按记录时间降序，同一时刻以来源和数字流水号决定稳定顺序。`resourceType` 与 `resourceRef` 精确匹配日志中的对象类型和编号；`createdFrom/createdTo` 对操作记录时间生效。续页传回 `cursor=nextCursor`，不保证跨请求快照。该投影合并已记录的业务操作和退款请求的已知结果，只返回操作人摘要、错误分类、追踪号和定位对象所需的字段。前端入口为「运行与准入 → 操作日志」。

所有业务ID为字符串，金额／数量／版本为JS安全整数。通用管理列表行不暴露内部游标；翻页读取Page.nextAfter。列表也携带若干详情的安全标量字段，具体以OpenAPI为准，页面不需要额外调用详情来获得这些字段。

退款原数组接口继续以末条 `cursor` 翻页，不改成 Page。退货验收继续使用详情响应ETag作为If-Match；不使用inspection的实收编号代替RMA申请编号。

## 实际权限矩阵

| 授权角色 | JWT必须包含 | 允许读取 |
|---|---|---|
| 租户交易管理员 | trade.admin | 本租户订单、支付／收款、退款、退货、报价及操作日志的安全投影 |
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

全局操作日志依赖 `V053__operation_audit_query_indexes.sql`，对既有审计表追加租户、对象与时间查询索引。由 Flyway 按版本应用，不需要补写历史日志。

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

V045 为支付和退款增加业务编号及唯一索引。新支付使用 `TRD-PAY` 编号，退款展示和查找使用 `TRD-RFD`，V1 证据中沿用原退款引用。订单、支付及退款详情兼容旧号和可读号，真实资金动作继续使用返回的原生标识。补录工具支持 `trade-payments` 和 `trade-refunds`，记录以不可变主键辨别，旧渠道请求号、回调和审批证据按原链路处理。

## 退款操作审计

使用现有 `trade.admin` 作用域和退款父资源授权。接受可读退款编号及有效旧编号，未知和跨租户退款均返回 404。省略 `limit` 时每页最多 50 个事实，可传 1–100 调整页容量。继续读取时原样传回 `nextCursor`，每次读取仍按原规则留痕。现有控制台从退款详情按需加载。

实际申请、审批、渠道提交和终态的审计与本地交易、资金和原事件同事务。重试保留原提交引用。审批验真失败沿用原入口的独立拒绝证据。审核和退款原因、签名原文、渠道账户及付款信息不属于管理审计查询的输出字段。V051 新增尝试和结果表，并向原 `tc_operation_audit` 追加可空关联和状态字段。版本依据为当前主线基线 V050。

专项 MySQL 覆盖申请与审批重放、渠道成功写审计失败后整体回滚、授权读取及跨租户拒绝。真实 OIDC 控制台浏览器验证同时看到已提交和重试的退款申请，两条记录指向同一提交。

## 控制台数据补齐

新增编号查询形式 `/numbered-pages/{resource}`、`/numbered-operations/{resource}`、`/operations/financial-fact-pages`，只接受显式允许的资源和筛选。详见 [OpenAPI](openapi.json) 和[页面及能力清单](design/console-data-completion/PLAN.md)。任务仍需诊断权限，退款日志仍需要通过退款父资源校验并记录查阅。工作台 `/operations/analysis` 按北京时间自然日和原半开区间汇总。

库存预警查询 `/inventory-alerts` 返回同范围全量状态统计与所选状态的精确页。可用量为零是缺货，配置了阈值且正的可用量低于或等于阈值是低库存。阈值版本独立于库存流水版本。商品展示图由管理员保存 HTTPS URL，服务端只存储，不拉取图片。V054 仅增加这些运营字段，历史行默认未配置。

商品草稿导入与批量上下架保留现有目录权限、发布不变量和幂等。订单 CSV 导出只包含安全列，同一快照内读取当前筛选的全量结果，最多 50,000 行。命令支持 `Idempotency-Key`，错误沿用现有协议。

商城、营销、权益工作台分别提供来源入口，按其自身应用登录和授权。控制台 `config.js` 可设置 `COMMERCE_WORKSPACE_URL`、`MARKETING_WORKSPACE_URL`、`BENEFIT_WORKSPACE_URL`。本机默认端口为 8602、8084、8083，其它环境按部署填写 HTTPS 地址。 `/operations/linked-checkouts` 只有业务证据权限可读取实际交易绑定及预算回执引用。
