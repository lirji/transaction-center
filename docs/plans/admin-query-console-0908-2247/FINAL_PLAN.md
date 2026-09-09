# 实施计划：管理查询接入控制台

日期：2026-09-08。决策见同目录 `DECISION_RECORD.md`。合同：`docs/ADMIN_QUERY_API.md`、`docs/openapi.json`。代码只改 `frontend/apps/console`。

## goals

- 运营可按编号/状态/会员搜本租户订单，从订单下钻支付、尝试、实收、退款、退货、报价。
- 退款队列增加筛选与详情；退货有列表并可看验收摘要。
- 有额外 scope 时可看订单证据、会员资格评估、任务租约；无 scope 不露出入口。
- 全部数据来自管理查询 API；无页面内 Mock；无批准退款、无改资金、无任务重试。

## non-goals

- 会员商城、C 端下单、付款跳转、退款审批、任务领取/解锁。
- 独立 H5 / 手机端重设计（沿用 860px 侧栏折叠即可）。
- OpenAPI codegen、跨租户查询、模糊关键字、地址明文。
- 替换 Outbox 页；通用 jobs 只观察。
- 假定本机 8400 已有 V033；开发用 mock fetch 测，联调另开后端。

## 视觉方向与设计参考

沿用 `frontend/apps/console/src/styles.css`：`--ink-*`、`--teal`、`--canvas`、`--paper`、`--radius-md`、`.panel` / `.data-table` / `.state-banner` / `.kv`。页面结构：`PageHeader` → 权限/语义 `StateBanner` → 筛选 `Panel`+`Field` → `Panel.table-panel` + `QueryGate`/`PageQueryGate` + 分页。详情：`page-grid` 主 `kv` + 关联表。金额：`formatMinor` + `.mono` 最小单位。

## 路由与页面流

```
/orders                         订单列表
/orders/:orderNo                订单详情（枢纽）
/payments                       支付意图列表（可 ?orderNo=）
/payments/:paymentNo            支付详情 + 尝试表
/receipts                       实收列表（可 ?orderNo=&abnormalOnly=）
/receipts/:receiptNo            实收详情
/quotes                         报价列表（P1）
/quotes/:quoteId                报价详情（P1）
/refunds                        现有队列 + E01 筛选；行链 /refunds/:refundNo
/refunds/:refundNo              退款详情（只读）
/returns                        现有申请/验收 + A09 列表
/jobs                           任务列表（jobs.read）
/jobs/:jobId                    任务详情
履约页 /fulfillment?orderNo=    从订单详情预填，仍手填 deliveryRef
```

证据不单独占导航：订单详情在具备 `trade.admin.evidence.read` 时显示「交易证据」面板；会员页在同 scope 下提供资格评估表单（`POST .../eligibility:query`）。

主路径：搜单 → 订单详情 → 关联列表（orderNo 过滤，不 N+1 详情）→ 点 paymentNo/receiptNo/refundNo/returnNo/quoteId 进详情 → 需要时「声明履约」带 query 到履约页。

## 组件树（复用 vs 新建）

**复用：** `PageHeader` `Panel` `Field` `Badge` `StateBanner` `EmptyState` `QueryGate` `MutationBanner` `CursorPager` `ConfirmDialog` `formatMinor` `describeProblem` `RequireScope` `renderWithApp`。

**新建：**

| 名称 | 路径 | 说明 |
|---|---|---|
| `pageSchema` / `parsePage` | `src/shared/api/schemas.ts` | `{items,nextAfter,hasMore,observedAt}` |
| `pageQuery` | `src/shared/api/keys.ts` | string `after` + 过滤参数；忽略空值；不发未知键 |
| `PagePager` | `src/components/ui.tsx` | `hasMore` + `nextAfter`；「回到首页」after=`0` |
| `PageQueryGate` | `src/shared/ui/QueryGate.tsx` 旁或同文件组件 | 空态看 `items.length`，不要把 Page 对象当 array |
| `FilterBar` | `src/shared/ui/FilterBar.tsx` | 一排 Field，提交时清 after |
| `TimeRangeFields` | 同上 | 成对 datetime-local → UTC RFC3339；单边/跨度>90天客户端拦截 |
| 各 `*Page` | `src/features/orders|payments|receipts|quotes|jobs/` | 见文件清单 |

侧栏新增分组「交易查询」：订单、支付、实收、报价。任务放「运行与准入」。

**导航 AND scope（必须改代码，不能只写 scopes 数组）：** `AppShell` / ⌘K 现用 `hasAnyScope`（OR）。给 `NavigationItem` 增加 `requireAll?: string[]`。任务项：`scopes: [TRADE_ADMIN]`，`requireAll: [TRADE_ADMIN, TRADE_ADMIN_JOBS_READ]`。过滤条件：`hasAnyScope(item.scopes) && (!item.requireAll || item.requireAll.every(hasScope))`。订单详情证据面板、会员资格表单：`trade.admin` 已由路由保证，再 `hasScope(TRADE_ADMIN_EVIDENCE_READ)` 才渲染。Jobs 路由：`Admin` 外包 + 内包 `RequireScope anyOf={[TRADE_ADMIN_JOBS_READ]}`。`testAuthState` 默认**不**含诊断 scope；相关测试显式传入 scopes。

## 状态与边界（逐页）

共性：loading=`StateBanner loading`+路径文案；error=`describeProblem`；429 title「管理查询繁忙」、detail 含「约 1 秒后重试」+ 可选「重试」按钮（`refetch`，不自动连打）；503 `ADMIN_QUERY_UNAVAILABLE`；证据 `EVIDENCE_DISABLED`/`EVIDENCE_UNAVAILABLE` 用 API `code` 分开展示，禁止拼零值。改筛选 `after='0'`（字符串）。列表 footer 显示 `observedAt`，注明跨页非快照。所有新管理查询 `useQuery({ retry: false })`。

**Gate / 分页 / 筛选来源（强制）：**

| 页面 | Gate | 分页 | 筛选状态 |
|---|---|---|---|
| 订单/支付/实收/报价/任务列表、退货 A09 表、支付 attempts | `PageQueryGate` | `PagePager`（`nextAfter` 字符串 `'0'`） | URL search params |
| 退款队列 E01 | 现有 `QueryGate`（数组） | 现有 `CursorPager` + 行 `cursor` | URL search params 引导 `orderNo`/`status`，其余可 local |
| 订单详情主记录 | 整页 loading/404 | 无 | 路径 `orderNo` |
| 订单详情四个关联 Panel | **各自** loading/error/empty，一个 429 不挡主详情和其它 Panel | 各表自己的 Page 或退款数组 pager | 固定 `orderNo`，退款 `status=ALL` |

**筛选非法（客户端，不发请求）：** 时间窗缺一边或跨度>90 天 → `TimeRangeFields` error；`memberId` 不符正整数文本 → Field error；实收 `abnormalOnly=true` 且 `disposition=NORMAL` → Field error 且 `enabled: false`。服务端 400 仍走 `describeProblem`。

| 页 | empty | 特殊 |
|---|---|---|
| 订单列表 | 当前筛选无订单 | 升序非最新；零元 `paidAt=null` 正常 |
| 订单详情 | 404 | CLOSING 不得当取消成功；关联表空不挡主详情；履约 CTA 需 `trade.fulfillment.write` 且无 fulfillment 或未完成时显示 |
| 支付列表/详情 | 无意图 | `UNKNOWN` 尝试≠失败；`closeFenceAt` 不是渠道关闭证明 |
| 实收 | 无收款 | `abnormalOnly` 与 `disposition=NORMAL` 互斥；`unreservedAmount` 非退款授权 |
| 退款队列 | 该状态无单 | 默认 PENDING_APPROVAL；ALL 显式传；无批准按钮 |
| 退款详情 | 404 | 不展示用户 reason |
| 退货列表+详情 | 无申请 | status 仅 OPEN/INSPECTED；inspection.recordedAt 是登记时间 |
| 报价 | 无报价 | `observedAt>=expiresAt` 可标过期，未过期≠可下单 |
| 证据 | 无 scope / 503 | 非营销资格 |
| 资格 POST | 表单校验 | 仅两枚举；`REGISTERED_DURING_CAMPAIGN_V1` 必填 campaignStartAt |
| 任务 | 无 scope / 空 | 只读；leaseExpired 是观察值 |

## API 契约（前端）

前缀 `/api/v1/trade/admin`。只读，不发 Idempotency-Key / If-Match。

- 新列表：`after` 字符串默认 `"0"`，`limit` 1–100，响应 Page。
- `GET /refunds`：仍数组；`status` 下拉含 **ALL**（显式传参）；翻页仍用末条 `cursor`。客户端用独立 `refundQueueQuery`（不要复用 `pageQuery`）。
- 退货：**列表** `adminReturnViewSchema` + Page；**详情** `adminReturnDetailSchema`（含 `inspection`/`observedAt`）。`api.returnByNo` 改用详情 schema。旧 `returnViewSchema` 可改为 detail 的别名或删除引用，测试 fixture 补新必填字段。
- 未知 query 键会 400：客户端只序列化有值的已知参数。

`describeProblem` 增加 429。验收仍用申请 `version` 做 If-Match，不用 `inspection.receiptNo` 当 returnNo。

## 响应式与移动端

- 断点：1100px `.page-grid` 单列；860px 侧栏抽屉。**复用**已有 `.table-panel { overflow-x: auto }`，不为横滑新写一套。
- 各新列表（订单/支付/实收/报价/任务/退货表）：筛选在 Panel 内纵向 Field，1100px 下与表单已单列。
- 订单详情枢纽：多 Panel 纵向 `.section-gap`；窄屏不改成 Tab。
- 退款多出来的筛选行：同一 Filter Panel 内堆叠，不并排挤表格。
- 支付详情 attempts 表同样横滑 + `PagePager`。
- 非目标：独立 H5、藏列、卡片列表、44px 全面改造。
- 验收：1280px 走通订单→支付→退款；860px 点检订单列表、订单详情、退款筛选，表可横滑、侧栏可开。

## 文件级改动清单

```
src/shared/auth/auth-context.ts          + TRADE_ADMIN_EVIDENCE_READ / JOBS_READ
src/shared/api/keys.ts                   + pageQuery、refundQueueQuery
src/shared/api/validate.ts               + parseTimeWindow / parseMemberId
src/shared/api/schemas.ts                + Page 与各 Admin* View/Detail
src/shared/api/client.ts                 + 15 查询方法；refunds 签名扩展；returnByNo 用扩展 schema
src/shared/api/client.ts                 describeProblem 429
src/shared/ui/PageQueryGate.tsx          新建（若 eslint 不允许与 QueryGate 同文件导出非组件则分开）
src/shared/ui/FilterBar.tsx              新建
src/components/ui.tsx                    + PagePager；可选 .kv 窄屏
src/styles.css                           筛选条、kv 窄屏
src/app/navigation.ts                    交易查询分组 + jobs gated
src/app/AppShell.tsx                     导航 requireAll + ⌘K 共用 visibleNav
src/App.tsx                              新 lazy 路由
src/features/orders/OrdersPage.tsx
src/features/orders/OrderDetailPage.tsx
src/features/payments/PaymentsPage.tsx
src/features/payments/PaymentDetailPage.tsx
src/features/receipts/ReceiptsPage.tsx
src/features/receipts/ReceiptDetailPage.tsx
src/features/quotes/QuotesPage.tsx
src/features/quotes/QuoteDetailPage.tsx
src/features/jobs/JobsPage.tsx
src/features/jobs/JobDetailPage.tsx
src/features/refunds/RefundsPage.tsx     E01 + 行链接
src/features/refunds/RefundDetailPage.tsx
src/features/returns/ReturnsPage.tsx     A09 列表 + E02 展示
src/features/fulfillment/FulfillmentPage.tsx  读 orderNo query
src/features/overview/OverviewPage.tsx   去掉「没有全量订单列表」；链到 /orders
src/features/members/MembersPage.tsx     有 evidence scope 时资格表单
测试：各新页 + schemas + refunds ALL + 无批准回归
e2e/console.spec.ts                      mock 新 GET；仍断言无批准
```

不改后端、不改 OpenAPI。

## 按依赖排序的实施步骤

1. **合同层：** schemas（含 `adminReturnViewSchema` / `adminReturnDetailSchema`）+ `parsePage` + `pageQuery` + `refundQueueQuery` + client 方法 + 429 文案 + scope 常量。单测 schema、时间窗、异常互斥、`status=ALL`。
2. **分页/筛选 UI：** `PagePager`、`PageQueryGate`、`TimeRangeFields`、`AppShell requireAll`。
3. **P0 订单：** `App.tsx` 注册路由 + 导航「订单」；列表+详情；详情关联 Panel 复用列表行组件（与步骤 4 共享 `PaymentRow`/`ReceiptRow` 等，可先内联再抽）。同步改 Overview 文案，避免仍写「没有全量订单列表」。
4. **P0 支付/实收：** 独立列表/详情路由；支付详情 attempts 用 `PageQueryGate`+`PagePager`；实收互斥筛选。
5. **P0 退款：** E01 `refundQueueQuery` + 下拉 ALL；`/refunds/:refundNo`；从订单链入时读 `?orderNo=&status=ALL`。保留无批准文案与测试。
6. **P0 退货：** 列表在现有页上方，点行设置 `activeNo` 拉详情（不强制新路由），走原验收；fixture 补 detail 字段。履约读 `?orderNo=`。
7. **P1 报价**（路由+导航）。
8. **P1 证据面板 + 会员资格**（scope + 503 分码）。
9. **P1 任务**（requireAll + 直链 RequireScope；`testAuthState` 不含 jobs）。
10. **e2e：** 为 Overview/新列表补 mock，避免 catch-all 404；退款无批准保持。

## 测试策略

- Vitest + stub `fetch`（`renderWithApp`），不打 8400。
- 必测：Page envelope 翻页用 `nextAfter`；退款仍数组；`status=ALL` 出现在 URL；改筛选 after 归零；无批准按钮；缺 jobs scope 无导航/直链空态；channel 异常互斥不发请求；资格缺 campaignStartAt 不发 POST。
- E2E：工作台仍可进；增加订单列表 mock；退款无批准保持。
- 视口：组件测试不强制；实现后若有浏览器工具再人工 860px。

## 验收标准

- 能按 orderNo/status 列出订单并打开详情，看到冻结商品与履约摘要。
- 详情能列出该单支付/实收/退款/退货，点击进入对应详情。
- 退款详情只读，无批准/驳回。
- 退货列表可点进原验收流；inspection 有则展示数量与 recordedAt。
- 无 evidence/jobs scope 侧栏无任务、订单无证据面板。
- 860px：订单表可横滑，筛选可用。
- `pnpm lint` `tsc` `pnpm test` 通过。

## 风险与回滚

- 8400 无 V033 → 页面 404：错误条即可，不 Mock 业务行。
- 扩展 `returnViewSchema` 导致旧 mock 缺字段：测试 fixture 补字段；生产后端应已增强。
- 配额 429：查询 `retry: false` + banner。
- 回滚：删新路由/feature 目录；退款/退货增强可 git revert 单文件；不改库。
