# 交易中心持久化分层设计

> 时效说明（2026-09-09）：本文保留早期 Mapper 分层切片记录；正文的 HTTP 数量和当时待实现范围不是当前总量。当前 OpenAPI 为69路径/81操作（2026-09-09文件统计），最新交付状态见 [状态页](PROJECT_STATUS.md)，整体分层与数据现状见 [项目架构](PROJECT_ARCHITECTURE.md) 和 [数据架构](DATA_ARCHITECTURE.md)。

## 决策

采用MyBatis Mapper接口＋XML，SQL不写在生产Java、SQL注解或Provider中。Starter固定4.0.1，复用Spring Boot 4.0.2、现有Hikari数据源及Spring事务，不增加数据库或其他中间件。

已核对[官方兼容矩阵](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)及[4.0.1发布说明](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-4.0.1)，并完成本项目真实MySQL集成测试。

本次只替换持久化实现，不改变业务逻辑或HTTP合同，不修改已执行V001～V003。工作区原有多SKU内部实现及V004保留；报价表存在不代表报价业务已完成。

## 调用与包结构

Controller解析可信身份，Service负责校验、状态流转及事务编排，调用所属模块Mapper；MyBatis加载XML，通过同一数据源访问MySQL。只读model由Service和Mapper共同引用，不反向依赖Service。

```text
transaction-<domain>/
  src/main/java/com/lrj/trade/<domain>/
    *Service.java             业务校验、状态流转、事务编排
    *Port.java                提供给其他模块的业务能力
    model/*.java              强类型只读模型，无持久化框架依赖
    mapper/*Mapper.java       明确方法、类型和@Param参数
    mapper/typehandler/       必要的时间、JSON转换
  src/main/resources/mapper/<domain>/
    *Mapper.xml               SQL、绑定参数、显式resultMap
```

保持模块化单体，不为此次隔离增加空转发Repository层；后续领域聚合需要组合多个Mapper时，再增加有实际职责的Repository适配器。跨模块只通过业务Port，不引用其他模块Mapper或直接访问他域表。

## 重点内容

| Mapper | 模块 | 职责 |
|---|---|---|
| CommandsMapper | core | 幂等占位、当前读锁定、结果写回 |
| OutboxMapper | core | 同事务追加内部事件 |
| AuditMapper | core | 同事务追加审计 |
| MemberMapper | member | 会员、空消费事实、同意历史 |
| ConsentDocumentMapper | member | 不可变条款版本 |
| CatalogMapper | catalog | 主规格兼容入口、商品状态与发布快照 |
| SkuMapper | catalog | 多规格草稿、发布快照读取 |
| InventoryMapper | inventory | 仓库、余额条件更新、预占与流水 |
| SeedDatabaseMapper | server | 查询实际连接库名，保护开发种子范围 |
| QuoteMapper | catalog | 报价主表、不可变明细和本人历史查询 |
| MerchantMapper | order | 逻辑商户配置及冻结 |
| OrderMapper | order | 订单快照、密文地址、取消／支付许可屏障、订单任务锁定及有界升级扫描 |
| MemberTradeFactMapper | member | 首单事实锁定、永久订单去重与不可逆消费累计 |
| ChannelMapper | payment | 不可变渠道绑定和配置摘要 |
| PaymentMapper | payment | 意图、尝试、收款、账本、可靠关闭及升级补漏引用 |
| JobLeaseMapper | jobs | 同事务入队／预约／持久唤醒、到期领取、令牌及业务引用校验／续租／完成／防丢唤醒退避、内部租户游标发现 |
| CallbackMapper | payment | 可信密文接收、事件去重和处理／核对状态 |
| PaymentRiskMapper | payment | 风险历史追加、当前指针读取/切换、一次性决定写入；全部绑定租户 |
| PaymentRefundMapper | payment | 正常实收定位、真实收款退款额度、永久hold、派发/终态及唯一退款资金账本，13个操作 |
| OrderRefundMapper | order | 冻结运费税额与订单退款财务证据版本，2个操作 |
| RefundMapper | refund | 持久政策、永久申请、组件额度/明细、审批及执行状态、原渠道退款号归属定位、有界恢复补漏，21个操作 |
| RefundInboxMapper | refund | 退款证据持久插入/事件当前读/按编号读取/完成/核对，5个操作 |

当前V016源码共22个Mapper/171个SQL操作，新增操作为退款恢复有界补漏；此次结构要求不改变业务逻辑及HTTP合同。V015已验证基线为22个Mapper/170个SQL操作，共享执行状态以CODEX_PROGRESS及验证记录为准；下述21/164为V014历史统计。

初次重构覆盖60个SQL操作，经报价、订单、消费事实、支付、租约、回调、全租户发现、风险及退款资金/申请扩展后，目前21个Mapper共164个操作（PaymentRefundMapper另增加1个正常实收定位），均有明确方法签名，不提供任意SQL执行方法、Object可变参数或通用参数Map。原Service内部record移至model包；仓库内调用点已同步。若外部Java调用方直接依赖旧嵌套类型，需调整import后重新编译；原HTTP JSON不受影响，退款应用另增加4个操作。

## 一致性与安全

1. Commands仍使用REQUIRED本地事务及10秒超时；Mapper加入同一数据源连接。审计／Outbox继续要求MANDATORY，不自行提交，不使用独立事务，不在数据库事务内等待外部网络。
2. 保留tenant_id、版本条件、FOR UPDATE／FOR SHARE和库存非负条件，调用次序不变；更新行数仍由业务层判断。
3. 使用绑定参数，禁止文本替换。租户来自已验签身份，不能由客户端覆盖；参数化不能替代租户条件。
4. 禁用二级缓存，一级缓存范围为STATEMENT，锁定读必须访问数据库。单语句超时10秒，同时受剩余事务超时约束。
5. 显式constructor resultMap及结果别名，避免同名ID列混淆、BIGINT精度丢失和nullable版本变为0。
6. UTC转换器仅在指定XML字段引用，保持原getTimestamp().toInstant().toString()语义，不全局覆盖String。规格JSON映射成不可变Map，业务层仍负责合法性及规范化。
7. DDL只由Flyway管理，保留中文表／字段注释。种子通过业务服务入库。测试允许直接JDBC作独立断言，生产业务代码不得绕过Mapper。
8. `JobLeaseMapper.nextTenant(type,after)`是唯一内部跨租户只读发现例外，无HTTP入口，强制类型、游标、索引及LIMIT 1；其余业务查询／写入仍必须显式tenant。结构门禁只对这个完整方法名豁免，不放宽整个jobs模块。

## API与兼容性

Mapper重构保持原22个HTTP操作／17个路径不变，随后报价切片新增6个操作，订单阶段再新增7个操作，支付核心再新增4个操作，退款申请层再新增4个操作，目前43个HTTP操作／33个路径，准确清单见[OpenAPI](openapi.json)。Mapper隔离本身不调整原HTTP状态码、resourceId、expectedVersion、分页、幂等键或错误码，前端无需因Mapper改造变更请求。新增退款申请返回202，不改变原接口合同。多SKU、报价、订单及支付本地核心已实现；退款申请层正在全量回归，真实退款渠道、耐久回调与恢复Worker仍待继续。

原商品创建仍建立主SKU，原商品编辑只修改主SKU价格，商品列表每商品一行。此行为来自本次开始前已有的多SKU修改，本次按原样抽离。

## 可验证验收

- Java层禁止内嵌SQL、SQL注解／Provider及直接JDBC执行；Mapper／model不反向依赖Service。
- Mapper与XML方法双向一致，绑定参数与@Param一致，业务SQL显式携带tenant；禁用文本替换及二级缓存。
- 隔离MySQL8.4验证外层事务同时回滚会员、命令、审计和Outbox；重复锁定读不得返回会话缓存旧版本。
- 保留幂等、租户隔离、防超卖、确认／释放竞争及HTTP合同测试；补充UTC毫秒、空版本、最大安全金额、引号文本和规格快照映射。
- 逐条比较60条SQL，规范化绑定占位、结果别名和空白后与改造前一致。
- 2026-09-07执行mvn verify，34项测试全部通过，0失败／0错误／0跳过；详见[验证记录](VERIFICATION.md)。

源码结构检查只是防回归门禁，不等同于完整字节码架构证明。Mapper隔离不自动获得三高认证，仍须完成原生产设计的容量压测、执行计划审查、故障演练、支付渠道和跨系统验收。
