# 交易中心代表页视觉调研

本页前半部分记录代表页验证阶段。正常入口以末节第一版实施记录为准。

基线为本机 Docker 和交易 `origin/main` 的 `93aed2d`。任务聚焦代表页。设计规范见 [DESIGN_SYSTEM.md](../../../DESIGN_SYSTEM.md)。

## 参考、观察与采用范围

| 参考 | 观察 | 对应本次设计 |
| --- | --- | --- |
| [Art Design Pro 源码](https://github.com/Daymychen/art-design-pro/tree/f3aaf58/src/views/dashboard) / [在线演示](https://www.artd.pro/) | console、analysis、ecommerce 拆开，营业指标与小图形组合，业务标签短而可扫读 | 浅色画布、现代中文排版、经营与运营采用不同空间权重 |
| [Tabler 源码](https://github.com/tabler/tabler/tree/05938bb/preview) / [在线演示](https://preview.tabler.io/) | 区域内指标、趋势和明细能连续对照，数值标签及比例条提供双重表达 | 同源趋势、显示数据口径、保留资金明细钻取 |
| [Medusa 源码](https://github.com/medusajs/medusa/tree/109089ab/packages/admin/dashboard/src/routes) / [官方订单截图](https://docs.medusajs.com/user-guide/orders/manage) | 订单商品、支付、履约按领域分组，查看与后续动作贴着对象 | 订单详情摘要、资金和售后分区、按证据事实推进 |
| [Shadcn Admin 源码](https://github.com/satnaing/shadcn-admin/tree/a156d9b/src/components/data-table) / [在线演示](https://shadcn-admin.netlify.app/) | 筛选、列可见性、表格排序和分页是一条作业路径，选中反馈低干扰 | 查询和表格合并表面，保留选列、焦点和明确分页 |
| [Saleor Dashboard 源码](https://github.com/saleor/saleor-dashboard/tree/3392f62/src) / [官方截图](https://saleor.io/blog/2025-february-update) | 统一列表筛选，销售渠道及独立对象形成清楚的作业语境 | 目录和订单分工，筛选条件持久化并可追溯。公开旧演示地址返回 404，使用官方产品截图 |
| [Vue Vben Admin 源码](https://github.com/vbenjs/vue-vben-admin/tree/bf51271/apps/web-antd/src/views/dashboard) / [在线演示](https://www.vben.pro/) | 运营工作台、分析视图及框架区域职责分明，组件状态与主题一致 | 统一标题/侧栏/控件规范，页面布局按作业差异化 |
| [Awesome Refine 目录](https://github.com/refinedev/awesome-refine#advanced-examples) / [Finefoods 源码](https://github.com/refinedev/refine/tree/2352eb5/examples/finefoods-antd) / [演示](https://example.admin.refine.dev/) | 从经营指标进入订单明细，CRUD 组件复用真实分页和查询状态 | 业务数据和前端展示分离，经营和列表使用真实 API |
| [Shopify](https://help.shopify.com/en/manual/shopify-admin/productivity-tools/searching-filtering-views) / [Stripe](https://docs.stripe.com/dashboard/basics) | 常用筛选视图紧邻数据，支付与余额口径明确 | 订单状态预设、金额范围与明细分离 |
| [Linear](https://linear.app/docs/filters) / [Vercel](https://vercel.com/docs/observability/insights) | 可分享的筛选、低干扰导航，指标在一致的项目语境内逐层查看 | URL 查询状态、当前范围示意、指标钻取 |

商业参考使用公开指南和官方截图，未进入付费账户。采用设计行为与信息组织。项目未复制任何参考源码、图标或截图。Art Design Pro、Tabler、Shadcn Admin 为 MIT。Medusa 核心 MIT 且企业包适用独立许可证。Tabler 中 ApexCharts 独立许可。Saleor Dashboard 使用 BSD-3-Clause，Vben 和 Refine 框架使用 MIT。具体许可见各项目根目录。

## 当前页面差异

- 工作台把期间表单、KPI、趋势和跨平台入口放在当前待办上方，收款与作业队列的主次随页面纵向滚动而变化。三个方案分别用经营摘要、待办、趋势作为主要视觉层。
- 订单现状先展示独立查询面板，再展示结果。真实分页、状态及资金信息已经齐备。预览结合筛选和结果，在高密度方案复用关联对象速览。数据方案的分布仅来自当前页记录，服务端总量另列。
- 商品现状把缩略图、商品名称、编号、选择嵌在同一列。预览将业务编号独立成列，把发布状态和应付价格信息放到可直接扫读的位置。
- 订单详情现状使用较大的等权摘要和七个同层分区。预览聚合到业务分组，沿用按需加载和已有深链接语义。
- 营销配置由独立的营销中枢处理。对照它的活动工作台、现有标准活动与 Offer 版本，使用自有权限和真实接口构建配置样板。交易侧只提供来源链接。

## 预览与验证

交易和营销样板均为 Vite 开发入口。交易使用现有 Casdoor 登录和 `trade.admin` 权限，营销使用当前运行配置与 `campaign:read` 等原有权限。交易保留默认业务路由，营销保留自己的服务和会话。已有系统和生产构建继续提供原路由。

- 交易工作台入口 `http://localhost:4180/design-preview/modern`，右上可切换 `operations` 和 `analytics`。三个版本用同一期间和币种比较。链接上的 `createdFrom`、`createdTo` 和 `currency` 可复现数据库有业务记录的截图范围。
- 订单入口 `/design-preview/modern/orders`，订单编号进入详情。商品入口 `/design-preview/modern/products`。营销入口 `http://localhost:4198/design-preview/campaigns`，按现有可见活动进入配置。
- 本地启动交易样板，在交易前端目录运行 `TRADE_UI_PORT=4180 pnpm dev --host ::1 --strictPort`。IPv6 的 `localhost` 使用已登记的 OIDC 回调。IPv4 的 `127.0.0.1:4180` 为 Docker 对照基线。
- 营销启动时使用已有 Vite 配置，给本地 4198 端口的开发进程代理 `/api` 和 `/config.js` 至本机既有营销运行端口 8084。开发服务仅监听本机，当前营销运行环境为已配置的隔离开发身份。
- 可复现验证入口为当前用户本机证据目录中的 `verify-layout.cjs`，原图由 `visual-review.html` 索引。不在 Git 中保存运行环境的业务截图及登录凭据。

### 可观察验收

在 Chromium 的 1440、1920、2560px 下分别检查五类原页和三种工作台/订单候选。截取 30 张预览图（包含营销活动选择页），页面外层无横向溢出。实际比较原界面及 Art Design Pro、Tabler、Shadcn Admin、Medusa、Saleor 和 Refine 的参考画面。宽屏图与 1440px 图都保留。内表滚动承接超宽字段，列表和报表分别设内容宽度上限。

| 路径 | 实际检查 |
| --- | --- |
| 订单 | 真实编号筛选，高级筛选、状态下拉与 Escape 恢复，速览关闭焦点，还原详情前的 URL 和当前列表。真实租户有 3 笔订单，验证了页容量和末页禁用。现有浏览器契约测试补充任意跳页和游标回前页 |
| 工作台 | 真实在库历史范围 2026-09-08 至 2026-09-15（北京时间，右端不含），日趋势键盘选择、图表与数据表切换、按期间进入同口径订单查询 |
| 商品 | 读取 2 条本机现有商品，以无图占位准确表达源字段，创建草稿弹窗打开、Escape 及焦点归位 |
| 复杂详情 | 状态和应付金额同屏，支付与实收子区、履约切换及返回。证据入口遵守原 scope，当前浏览器账户未开放证据 |
| 营销配置 | 读取 7 条现有标准活动。核对范围、版本、实际规则字段和审核发布入口。浏览器验收未提交或发布业务记录 |

实际对照改动：合并订单标题与表格工具栏，修复速览开合重挂表格导致的焦点丢失，给订单编号和币种稳定列宽。工作台把结果日期与趋势提到桌面首屏，CNY 摘要使用精确分转元显示。队列用依赖顺序读取，本地真实预览复测没有首屏 429。其他币种的摘要和资金底层明细保留 API 最小单位。

对照范围：Shadcn Admin、Tabler、Finefoods 浏览了实际演示；Saleor 和 Medusa 阅读源码与官方产品截图。Art Design Pro 的线上资源加载不完整，按所给源码版本在本机启动其官方示例，实际查看工作台并截图。Vben 已查看公开登录画面，其分析页和工作台按源码核对。商业产品走查使用公开官网和指南。用户需在候选中确认推广方向。

工程结果：交易 lint、build、53 项单测和 17 项现有 Chromium 契约测试通过。营销 lint、build 和 277 项单测通过。实际浏览器的读取、筛选、焦点和关键路径见本机证据。

## 第一版实施

- 工作台 `/` 优先加载待批退款、异常实收、对账差异和发布异常，保留对应队列条件。右侧概况和快捷操作使用当前租户真实数据。今日经营为北京时间 CNY 快照。
- 正式订单 `/orders` 使用状态预设、紧凑表格和当前页速览。详情按订单概况、资金、履约与售后、按权限显示的交易证据分组。沿用原列表筛选、分页参数和详情 `section` 深链，返回时恢复原作业上下文。
- 经营分析 `/analytics` 默认近七天，换用其他日期或币种时以 URL 记录当前范围。从指标、状态或资金明细进入对应的真实查询。经营汇总、相邻等长期间、明细权限和资金口径继续沿用原接口。
- 商品列表正式采用独立业务编号和发布状态预设。其他交易模块共享新外壳和视觉令牌，继续使用原业务入口和表单。
- 原开发候选入口保留，以供比较。正常业务入口不显示方案切换。交易使用本地开发实例验证，现有 IPv4 Docker 容器继续作为对照。
