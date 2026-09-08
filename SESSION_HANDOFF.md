# 交易中心与完整裂变活动：会话交接

> Git 发布说明：本文后续编号章节保留各阶段历史现场；“无提交/未推送”等旧描述不代表发布后的 Git 状态。2026-09-08 用户已授权仅 transaction-center 按能力分支提交、合并并推送 origin/main；结果与验证范围见 [Git 交付记录](docs/GIT_DELIVERY.md)。`.codex` 检查点与 CODEX_PROGRESS.md 为本机忽略文件，文中指向它们的证据链接仅在原开发工作区可用。代码发布不代表生产部署或完整裂变验收。

更新时间：2026-09-08。用户已要求继续并明确授权并行；归因、权威映射边界、纯规则SPI、编译器和参与持久化切片已完成；邀请令牌与控制面预览并行开发，独立审查另行推进。

**接手结论：订单归因已实现并独立审查，428项组合全量及3项修订专项通过，共享V032已执行。退款拒绝审计等已完成部分不重做。后续映射/原平台切片分别有专项证据，不包含在428项归因阶段全量内。当前推进邀请令牌、控制面校验仿真与绑定实施前独立审查；完整裂变仍未完成。**

## 1. 原始目标与执行顺序

原始目标：“继续完成后续内容，不用等我给你继续。最终的目的是实现裂变这一系列活动。”

先完成交易中心内部模块，再改造现有平台，最终完成邀请关系、资格、奖励、退款失效及追回、对账等完整链路。设计、代码、接口合同、测试、中文注释和原生产验收要求均保留，不能把交易中心测试通过解释为裂变整体完成。

权威依据：

- [原始目标](.codex/tasks/referral-end-to-end/OBJECTIVE.md)、[跨阶段约束](.codex/tasks/referral-end-to-end/DESIGN_CONTRACT.md)。
- [manifest.json](.codex/tasks/referral-end-to-end/manifest.json)：161个稳定需求ID及原文来源、验收定义。这些ID多数覆盖整节要求，不是161个简单功能点。
- [baseline](.codex/tasks/referral-end-to-end/baseline/)：冻结的交易中心及裂变设计原文。交易以00～03系列为入口，裂变完整实施清单见10-FINAL_PLAN，结合09-PRODUCTION_DESIGN和08-CROSS_PROJECT_CAPABILITY_AUDIT的补充核对。
- [PROGRESS.json](.codex/tasks/referral-end-to-end/PROGRESS.json)：当前检查点；根CODEX_PROGRESS为导航。部分逐项note仍保留“326项”等早期阶段历史，不据此回退工作，最新验证范围见本交接第5节及对应原始报告。

裂变设计首期是“邀请有礼”：注册新客或权威首单两种资格模式、直接邀请、双方独立奖励、人数阶梯、退款失效与补偿。后续好友助力、拼团、砍价、排行榜、多层奖励在原设计中列为独立迭代，尚未实现，不能声称邀请关系模型已覆盖这些玩法。试点券SKU、门槛、期限和档位是运营配置，不可把设计示例硬编码为业务决定。

## 2. 环境、现场和授权

- 项目：`/Users/liruijun/personal/LLM/transaction-center`；Java 21、Maven、多模块单体，SQL为MyBatis XML/Flyway。
- 当前Git：`main`，**尚无任何提交**，大量项目文件为untracked；不要把它们当临时垃圾清理。未提交、推送、合并或部署。本轮不创建提交。
- 同级原平台位于`/Users/liruijun/personal/LLM/`：marketing-lowcode-platform、benefit-center、risk-platform、recon-platform、auth-platform等。改动前重新检查各自AGENTS及工作树，保留已有变更。营销前端曾有用户/其他工具改动，不覆盖。
- 前端交给Cursor；Codex负责后端、接口、数据库与领域逻辑。
- 已获授权在验证后执行数据库迁移。共享MySQL为dev_infra的`dev-infra-mysql84-1`，映射端口43306，业务库`transaction_center`。测试使用隔离MySQL容器，不能混淆测试库和共享库。
- **共享V001～V032已执行并冻结**。最新48张业务表、678个字段，表/字段中文注释缺失均为0。下一个未占用迁移号V033。
- 归因全量20970、最终专项94440、共享迁移34570均已退出0，不轮询旧句柄。当前并行Agent为mapping_adapter/referral_runtime；实际新测试句柄与进度以PROGRESS最新检查点为准。dev_infra基础设施无需停止。
- `/tmp/migrate_verified_trace_context.py`和此前迁移/生成脚本已经执行，禁止按旧摘要重跑；不要依赖新会话一定还存在/tmp文件。正式迁移入口是[scripts/MigrateDatabase.java](scripts/MigrateDatabase.java)。凭据从环境/基础设施取，不复制进交接或输出日志。

必须保持：网络调用在业务事务外；跨模块用Port；订单/库存/资金事实/Outbox原子一致；UNKNOWN不当作失败、不释放库存；退款不自动实物回库；退货凭权威验收唯一入库；everPaid不因退款清除；同幂等键不同内容拒绝；新表每字段及表本身有中文注释。

## 3. 已完成的内部能力，以及各自仍欠什么

下表“已完成”指内部实现和相应证据，不等于生产签收。

| 能力 | 已实现、不要重复开发 | 仍有剩余 |
| --- | --- | --- |
| 会员/商品/报价/库存/订单 | 身份隔离、会员状态/同意、商品SKU发布快照、报价、库存预占/流水、订单支付取消基础链路 | 匿名化展示、完整归因、权威主体跨平台映射；生产规模验收 |
| 支付 | 渠道绑定、本地意图/尝试、风险许可、UNKNOWN恢复、回调Inbox、可靠关单/取消协调、异常实收事实 | 选定真实支付渠道并接签名/创建/查询/关单；真实环境验收和KMS |
| 退款及审批 | 资金预占上限、申请/审批/拒绝、真实RSA审批HTTP验证、**拒绝审计及ARJ关联**、回调/恢复/补偿；异常收款只生成唯一补偿申请 | 真实退款渠道、真实审批流程方集成、外部执行及资金核对 |
| 履约与退货 | 履约/完成/退货验收、唯一入库、授权业务入口、会员确认须已有真实交付 | 实际WMS/交付来源验真、生产联调 |
| 新客/首单事实 | 原生会员消费事实、权威本地首次确认支付口径、退款不重置首客、订单退款累计证据 | C02/C03的canonicalSubject、organization/shop映射与外部消费者合同 |
| V1事件与发布 | 7种事件、JSON Schema、事务Outbox、真实开发Kafka验证、隔离/重放/永久凭证、历史维护/补漏 | 生产Kafka ACL/HA、消费者验收、实际告警投递和容量/故障验证 |
| 对账 | 资金事实扫描、差异案件、受控操作、独立QUERY计划及恢复 | 真实渠道账单、外部对账/补偿端到端签收 |
| 管理版本合同 | 10处管理操作If-Match，404/409/412/428区分、ETag、永久重放边界 | 外部调用方升级与消费者合同验证 |
| 缓存/热点 | 商品版本缓存、单飞/有界降级、热点SKU保护，实际Redis专项 | 生产缓存故障/容量/延迟验收，不能无依据另建库存分桶 |
| 资金观测 | UNKNOWN/退款预占/回调/差异9类状态、10001计数下界、覆盖索引、故障保旧样本和新鲜度 | 实际监控采集/通知、生产阈值与容量校准 |
| 跨服务追踪 | HTTP→持久Job→8类Worker→V1事件/Kafka，线程/MDC恢复、重试保留首个父头、旧任务NULL安全新根 | 真实渠道HTTP适配器传播、collector/exporter、生产采样、外部消费者 |
| 机器合同/错误SDK | OpenAPI/AsyncAPI、7事件12正例、离线合同检查；独立Java错误SDK，75生产码+1设计预留码、保守重试建议 | 包仓库发布、原平台实际消费及消费者驱动合同、安全/远程CI |
| 自动准入 | 人工策略AND自动保护；积压/磁盘滞回，启动/失效关闭，单调时间寿命，停止并发隔离；暂停仍可回调/退款/重放 | 实际数据库存储卷映射及生产预算；默认关闭，不能以API根分区磁盘代替DB卷 |

特别注意：SDK不执行网络重试、不生成新幂等键；未知支付建议查询原交易，未知错误码保留并停止自动重试。`Retry-After`仅缺失时默认1秒；非法、日期、过大值停止自动重试，不能提前重试。

自动准入通过单调时间扣除样本原有年龄；失效样本CAS废弃，防止墙钟回拨复活。过期样本先拒绝，再处理滞回；自动恢复不覆盖人工暂停。

## 4. 尚未开发/尚未完成的主要工作

### 4.1 订单归因：内部实现已完成

[归因合同](docs/ORDER_REFERRAL_ATTRIBUTION_DESIGN.md)及V032已落地。默认关闭，未接通令牌明确拒绝；无token旧摘要兼容。固定RS256/issuer/JWKS/专用audience、有界取钥、实际禁止事务内验签；商户锁及地址加密后复检时限和绑定。订单/库存/Outbox/永久快照原子提交；tenant+issuer+jti一次消费，取消退款不释放。按actor+operation+key+完整请求摘要永久回放，Commands淘汰、令牌过期及密钥退休也不改原成功；去token/改地址拒绝。

组合全量428项/34套通过，之后3项测试断言修订定向通过，独立复核关闭测试覆盖缺口；共享V032/48表678字段注释完整，归因表0行。错误SDK及机器清单同步4个归因码，共80码（79生产发出+1预留）；OpenAPI包含可选referralToken。营销真实签发方尚未接通，不冒充完整裂变。

### 4.2 隐私和权威映射

- 匿名化/注销展示入口尚未实现；昵称、收货信息展示以及不可恢复交易资格需要业务确认。
- 原始地址/回调证据留存期限未确定；不得自动删除资金/身份/审计证据。
- C02会员与C03订单证据已有交易侧原生事实，但canonicalSubject及organization/shop权威映射、营销侧查询/事实消费尚未接通；不能把本地memberId或“查无历史订单”直接当作跨平台新客判断。

### 4.3 完整裂变：已有设计，主体实现仍待做

冻结实施清单：[10-FINAL_PLAN第9节](.codex/tasks/referral-end-to-end/baseline/10-FINAL_PLAN.md)。原平台现已新增referral-runtime-spi、REFERRAL_POLICY编译器及services/referral-service参与持久化基础。参与服务无公开HTTP，受信身份/发布来源默认拒绝，V1仅隔离数据库测试，未共享迁移。新会话实施前再次核对Agent和现场，避免重复开发。

- `services/referral-service`：参与者、邀请token/解析/绑定、首绑获胜、资格事实、观察期、双边及阶梯奖励、配额、持久任务/Inbox/Outbox、发布运行时、授权及对账。
- `runtime-spi/referral-runtime-spi`：确定性计划/规则求值；规则编译器、低代码方言、控制面活动类型/校验/仿真/审批发布、冻结版本及运行时readiness。
- 身份/BFF断言：签名、audience、租户/组织/店铺、method/path/bodyDigest/jti绑定；不能由前端自报受益人。
- 营销benefit-funding：新referral来源组装与授权、冻结SKU版本、稳定请求号、风险复评、最终履约回流；202不等于发奖成功，保留旧drools来源兼容。
- 权益中心：在既有按来源查询、履约事件和按item补偿能力上扩展SKU版本约束、取消栅栏等缺口，不能重复造一套履约/补偿系统。
- 风控/事件网关/measurement/recon：裂变风控新上下文、认证来源与事实路由、规范化累计证据、应发/实发及补偿核对；分析点击不产生奖励。
- 退款后资格失效：未发取消、在途查清、已发追回；取消与成功竞态保留双方事实；券已核销等不可追回情况进入人工处理，不能伪记追回成功。
- Cursor前端：活动配置/发布、邀请页面、个人进度与奖励、运营处理界面；从真实接口读取，Mock通过种子入库。
- 全链路集成、消费者合同、原规模种子、容量/并发/故障测试，以及完整发布验收。

### 4.4 生产与交付门禁仍保留

真实渠道及密钥托管、WMS/审批/主体权威来源、Kafka/Redis/MySQL生产配置、DB存储卷与阈值、监控/告警投递、容量与故障演练、多AZ/备份恢复、安全扫描/远程CI、前端联调、外部合同签收和生产验收。已有局部部署/告警/检查资产可复用，先读实际文件；本地模拟或开发中间件通过不能替代这些门禁。

## 5. 已验证的精确范围

| 验证 | 结果 | 原始证据 |
| --- | --- | --- |
| 归因阶段组合全量 | 428项/34套，失败/错误/跳过0，含SDK/自动准入 | [归因全量](.codex/tasks/referral-end-to-end/evidence/order-attribution/full/result.json) |
| 归因最终断言专项 | 3项通过，两quote同jti竞争及精确过期错误 | [最终专项](.codex/tasks/referral-end-to-end/evidence/order-attribution/final-focused/result.json) |
| 共享V032 | 48表678字段注释完整，归因0行 | [迁移](.codex/tasks/referral-end-to-end/evidence/order-attribution/shared-migration.txt)、[结构](.codex/tasks/referral-end-to-end/evidence/order-attribution/schema-check.txt) |
| 追踪阶段完整Maven验证 | 403项/31套，失败/错误/跳过均0 | [trace-context/full/result.json](.codex/tasks/referral-end-to-end/evidence/trace-context/full/result.json)及同目录XML |
| 追踪专项，含真实开发Kafka | 19项通过 | [trace-context/focused-result.json](.codex/tasks/referral-end-to-end/evidence/trace-context/focused-result.json) |
| 共享V031迁移 | 退出0；47表659字段注释齐全 | [迁移记录](.codex/tasks/referral-end-to-end/evidence/trace-context/shared-migration.txt)、[结构核对](.codex/tasks/referral-end-to-end/evidence/trace-context/schema-check.txt)、[中文原文](.codex/tasks/referral-end-to-end/evidence/trace-context/schema-unicode-check.txt) |
| 错误SDK | 4项单测通过、JAR已打包 | [SDK证据](.codex/tasks/referral-end-to-end/evidence/error-sdk/) |
| 合同脚本 | 7事件/12正例、9项Python检查通过 | [contracts.txt](.codex/tasks/referral-end-to-end/evidence/error-sdk/contracts.txt) |
| 自动准入初轮 | 21项通过，含真实数据库受理/退款/回调/重放边界 | [first-focused/result.json](.codex/tasks/referral-end-to-end/evidence/automatic-admission/first-focused/result.json) |
| 自动准入新鲜度修复后 | 16项纯单元/门禁通过，独立复审无剩余阻断 | [freshness/result.json](.codex/tasks/referral-end-to-end/evidence/automatic-admission/freshness/result.json)、[review.txt](.codex/tasks/referral-end-to-end/evidence/automatic-admission/review.txt) |
| 退款审批拒绝审计 | 已完成，原恢复起点不再待验证 | [approval-rejection](.codex/tasks/referral-end-to-end/evidence/approval-rejection/)及[approval-http](.codex/tasks/referral-end-to-end/evidence/approval-http/) |

**最新归因阶段428项全量包含SDK/自动准入及归因；随后3项断言收紧专项另计，不相加为431全量。正在并行的新映射/原平台切片不在428覆盖内，须各自专项验证。** 旧403/21/16保持历史证据，不重复全量。

常用命令（按当前改动选择，勿机械全部重跑）：

```sh
# 无数据库的合同核对
./scripts/verify-contracts.sh

# SDK独立构建
mvn -q -l build-error-sdk.log -pl transaction-client-sdk package

# 新归因阶段实现及专项通过后，再做一次组合全量
mvn -q -l build-next-stage-full.log clean verify
```

完整数据库回归上一阶段约8分钟，详细日志来自框架启动、迁移和集成场景。用户已反馈慢、日志过多：合并相关实现/验证；纯注释或测试边界修复定向检查；详细日志落文件，只汇报关键结果。不要反复编译、重启数据库或重复已通过的全量。

## 6. 最近改动的定位

- 追踪：`transaction-core/.../TraceContext.java`、`V1EventEnvelope`及事件工厂；`transaction-jobs/.../JobLease*`与8类Worker；`transaction-server/.../TraceContextFilter.java`、`KafkaEventPublisher.java`、`Errors.java`；V031。
- SDK：`transaction-client-sdk/`、根`pom.xml`、`docs/contracts/error-codes.json`、`scripts/contracts/error_contract.py`及反例；`scripts/verify-contracts.sh`。
- 自动准入：`transaction-core/.../AutomaticAdmissionGate.java`和`AdmissionService.java`；`transaction-server/.../AutomaticAdmissionSampler.java`、`AutomaticAdmissionConfiguration.java`、`DatabaseDiskSpace.java`、`application.yml`；`AutomaticAdmissionTest`及`FoundationTest`新增场景。
- 设计/说明：[自动准入](docs/AUTOMATIC_ADMISSION_DESIGN.md)、[SDK](docs/ERROR_SDK_DESIGN.md)、[追踪](docs/TRACE_CONTEXT_DESIGN.md)、[归因下一步](docs/ORDER_REFERRAL_ATTRIBUTION_DESIGN.md)、[验证](docs/VERIFICATION.md)。完整累计路径见PROGRESS.changed_files。

## 7. 待业务确认（未答复不视为同意）

1. 实际支付/退款渠道及其接口文档、测试环境和服务身份；目前未选定。
2. 已发出但未收到答复的隐私方案：注销后隐藏昵称/收货信息、禁止恢复新交易资格；保留支付/退款/审计事实，留存期限未确认前不自动删除原始证据。该提案不是已批准决定。
3. 生产部署前需确认真实DB卷映射、容量预算、权威主体/WMS/审批对接、奖励SKU及运营参数、外部合同等。它们可与内部开发并行准备，不要因尚未配置生产环境就停止所有内部实现。

## 8. 直接恢复

读取CODEX_PROGRESS与最新PROGRESS，先核对mapping_adapter/referral_runtime的状态和现场；归因428全量/3项最终专项/共享V032已通过，不重跑。权威映射边界、规则SPI、编译器、参与持久化已完成各自专项与审查；继续邀请令牌、控制面校验仿真，再首绑获胜、资格、规则发布、奖励/退款追回及全部原门禁。原营销前端已有改动必须保留，前端交Cursor。

## 9. 后续并行切片最新证据

- 交易映射边界：Unicode补强最终11项+Quality3通过，独立复核；来源未配置返回Pending，不提供跨平台READY。
- 原平台纯规则SPI：13项专项通过，独立复核；仅资格与奖励候选计算。
- 原平台编译器：29个唯一专项场景分批通过；新方言长度前缀hash、原始JSON严格类型、旧方言兼容，独立复核关闭问题。下一控制面切片会移动纯编译器，移动后另验证。
- 原平台参与持久化：前序12项、锚点与锁后身份修复后8项通过，覆盖15个唯一场景，不能相加为20项。16个源文件SHA与测试快照一致，独立审查通过。永久幂等、冻结版本、HMAC版本持久锚点、审计/Outbox原子提交；无公开HTTP、真实BFF和发布依赖默认拒绝。
- 原始证据位于同级marketing-lowcode-platform/docs/delivery/referral-{runtime,compiler,participation}/。这些均不在归因428全量覆盖内，也不代表平台全量/生产验收。
- 当前分工：mapping_adapter邀请令牌内部发行/解析（V2、事务外保护、短期密文回放、永久hash）；referral_runtime控制面校验/模拟（发布仍关闭）；attribution_review首绑持久链路实施前只读审查。根Agent负责整合与恢复记录。实际进程句柄以新消息为准。

## 10. 新收口与下一并行（覆盖第9节旧进行态）

控制面preview已50项组合专项+12修订专项通过并独立复核16源SHA；邀请token已15初轮+1新增摘要过期专项通过并独立复核29源SHA，V2未共享迁移。两者都不是整平台全量。奖励授权纯合同初轮9项通过，统一sourceRequestId自洽补强正在做10项最终验证，以后续PROGRESS为准。

下一分工：mapping_adapter首绑持久链路；referral_runtime权威累计订单/退款证据纯合并；attribution_review独立审查及benefit新来源实施前审查；root合同整合/文档/下一奖励衔接。绑定年龄起点询问未答复，使用受信绝对bindUntil接口默认拒绝，不自行选择业务起点。

## 11. 当前恢复点（2026-09-08，覆盖旧进行态）

最新正式检查点fee3bc15c8b94bf89dba0fe824207a65。奖励合同最终10、累计证据合并30、参与raw-time3、权益候选组装20专项及各自独立审查关闭；计数存在复用，不能相加称全量。首绑V3纯12已过，DB11尚无业务结果；权益SKU14纯+旧HTTP4已过，MySQL7 process27752仍初始化。Docker慢导致两库验证错峰，不重启共享环境；root测试单镜像inspect已越过ListImages挂起。mapping_adapter并行资格V4A纯准备/CAS边界，referral_runtime并行新CENTER永久prepare/receipt恢复纯状态机，reviewer负责独立审查。后续状态以CODEX_PROGRESS与最新检查点为准。未确认的身份来源、隐私、maxBindAge起点、真实渠道/生产参数继续默认拒绝或待确认。

## 12. 最新状态（覆盖以上旧进行态）

2026-09-08：V4A证据账本19纯+11真实MySQL通过并独立复核；V11耐久HELD受理25纯+8真实MySQL通过，16源指纹匹配并终审关闭。HELD只是本地待发送。当前并行V4B/V5资格落库与V12持续取消同步/投递前复核；root新增证据到资格任务fanout8纯通过，待V5联合数据库验证。权益SKU已本地提交30e4331，无推送或main合并。剩余完整资格、奖励账本/限额/配额持久化、发送恢复、退款追回对账、原平台发布/BFF/前端及全链路生产验收。最新详细状态与正式检查点见CODEX_PROGRESS，161项和未确认隐私/真实来源/渠道/生产参数均保留。
