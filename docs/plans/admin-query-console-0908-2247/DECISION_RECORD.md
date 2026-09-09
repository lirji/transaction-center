# 决策记录：管理查询接入控制台

日期：2026-09-08。范围：`frontend/apps/console` 接入 `docs/ADMIN_QUERY_API.md` / OpenAPI 已交付的只读管理查询。获批前不改业务代码。

## 1. 页面架构

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 按资源拆 feature 页（推荐）** | 与现有 `features/products/`、`features/refunds/` 一致：`/orders`、`/orders/:orderNo`、`/payments`、`/payments/:paymentNo`、`/receipts`、`/receipts/:receiptNo`、`/quotes`、`/quotes/:quoteId`、`/jobs`、`/jobs/:jobId`；增强现有退款/退货页 | 与 App.tsx lazy 路由、导航、测试模式一致；退款数组分页与新 Page 分页可局部处理；写操作页（验收、履约、政策）不必塞进只读 Hub | 侧栏条目增加 |
| B. 单一「交易查询」Hub + Tab | `/query/*` 一个入口，内部切订单/支付/收款 | 统一筛选与 URL | 偏离现有扁平导航；与 Returns/Refunds 写操作难共存；首包更胖 |

**裁决：A。** 订单详情作为下钻枢纽：同页用 `orderNo` 过滤拉支付/收款/退款/退货列表，不把关联资源做成嵌套子路由，也不对表格每行请求详情。

## 2. 筛选状态放哪

| 方案 | 取舍 |
|---|---|
| **URL search params（推荐，列表页）** | `/orders?status=PAID&orderNo=`、`/payments?orderNo=ORD-1` 可分享、可从订单详情深链；改筛选时清空 `after` |
| 仅 local useState | 与现网 Members/Refunds 一致，但不能从订单跳到「该单支付」并保留条件 |

**裁决：** 新列表页用 `useSearchParams`。退款队列同样用 search params 引导 `orderNo`/`status`（订单详情链 `/refunds?orderNo=…&status=ALL`），其余筛选可写在同一 search 或与 local 同步初始化。

## 3. 分页组件

| 方案 | 取舍 |
|---|---|
| 改造现有 `CursorPager` 兼容两种模型 | 调用方多，易改坏 Members/Outbox/Refunds |
| **新增 `PagePager`（推荐）** | 消费 `{hasMore, nextAfter: string \| null}`；`CursorPager` 继续服务数组 API |

**裁决：** 双组件并存。退款 E01 保持数组 + 行内 `cursor` + 现有 `CursorPager`。

## 4. 移动端适配

现有断点：1100px 栅格单列、860px 侧栏抽屉（`frontend/apps/console/src/styles.css`）。README 与工作台文案均声明这是运营管理台，不是 C 端。

**裁决：沿用既有适配，不另做 H5。** 筛选堆叠、`.table-panel` 横向滚动、触控 44px 全面改造与独立手机端列为 non-goals。验收至少在 860px 下表格可横滑、侧栏可开。

## 5. 视觉方向

**沿用现有视觉语言（非假设）：** `--canvas/#f4f6f5`、`--paper`、`--teal/#087f75`、`--radius-md 12px`、`PageHeader` + `Panel` + `StateBanner` + `Badge` + `Field` + `data-table`。不引入新组件库。参考站仅在缺口时用 SaaS Interface / 中后台列表+详情模式，映射到已有 tokens。

## 6. 证据 / 任务入口

缺 `trade.admin.evidence.read` / `trade.admin.jobs.read` 时：**导航隐藏**；直链进入则 `RequireScope` 空态（与 `RequireScope.tsx` 一致）。证据另受服务端开关，503 `EVIDENCE_DISABLED` 用错误条说明入口关闭，不拼零值。

## 7. 未采纳

- 不做退款批准按钮、任务解锁、真实付款跳转、关键字模糊搜、手机号搜单。
- 不把会员 `GET /orders` 当管理查询。
- 不在本期用 OpenAPI codegen；手写 Zod，与现有 `schemas.ts` 一致。
