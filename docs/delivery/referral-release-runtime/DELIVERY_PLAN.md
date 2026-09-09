# 裂变可信发布与运行许可实施方案

2026-09-09。下一步实施仓库：`../marketing-lowcode-platform`。本文是待审查方案，尚未实现或开放发布。

## 需求与代码依据

交易 T12 已完成并提交。下一阶段落实既有 PUB-01/PUB-02 合同，建立可恢复的可信发布状态，为后续真实参与提供发布依据。

- `services/marketing-control-service/.../ReleaseApplicationService.java` 的 stage 与制品校验均拒绝 REFERRAL；现有 `assertBenefitClosure` 只处理 decision，不能直接复用于裂变。
- `runtime-spi/referral-runtime-spi/.../ReferralPlanCompiler.java` 已输出 `CompiledReferralPlan(definitionId,scope,binding,policy)`；定义版本在制品签名引用中，不能从请求补一个版本。
- `marketing-contracts/.../release/` 已有 Manifest、ArtifactReference、RuntimeAck、ActivationDirective、KillSwitchDirective 及签名工具。
- `runtime-spi/journey-runtime-spi/.../JourneyReleaseVerifier.java` 与 journey-service 的持久发布服务可参考；不能直接替换 runtime 字符串后宣称裂变可用。
- `ReferralParticipationPermitPort` 已规定最多 10 秒的许可；`ReferralConfiguration` 当前默认返回 null。

## 可行性

结论：conditional-go。可先完成本地验签、持久状态及测试；真实发布开放受权益目录、活动映射和密钥配置约束。身份及结算政策未决不妨碍第一阶段，但会阻止真实参加与发奖验收。

优先采用现有签名合同和各服务本地 MySQL 事务。拒绝新增跨库 XA、纯内存激活状态、客户端自报 SKU 闭包或假 READY。控制服务与 referral 服务各自提交本地事务，通过已有发布消息/ACK 合同交互；重复和乱序由唯一约束及单调序号处理。

## 产品范围

面向发布管理员和 runtime 服务身份。流程：审核冻结规则 → 权威核验权益/SKU 版本 → 签名制品及清单 → runtime 验证并持久预热 → 签名 READY ACK → control 达到副本/容量条件后激活 → runtime 持久应用激活 → 短期参与许可。

预热成功只表示该制品可用于后续激活。许可还必须满足当前路由、活动范围和新鲜熔断状态。无可信状态、过期或依赖不可用一律拒绝新参与；历史成功重放沿用既有幂等规则。

本阶段不开发前端/BFF、不决定 canonicalSubject 或 maxBindAge 起点、不启用真实发奖、不部署共享服务。UI/UX 不适用：只新增后端可信发布能力，前端后续交 Cursor。

## 技术设计与文件范围

以下路径相对营销仓库；新增类名为实施目标，需遵循该仓库既有包约定。

| 模块/文件 | 变更 |
| --- | --- |
| referral-runtime-spi：新增 `ReferralReleaseVerifier` 及测试 | 固定信任键验证 manifest 与制品；核对 tenant/slot/ABI/checksum/定义/版本；严格反序列化并校验计划 |
| referral-service/application/release：新增发布应用服务、仓储 Port | 持久安装、幂等预热、ACK、激活序号和熔断状态；明确状态转换 |
| referral-service/infrastructure/persistence：新增 mapper/XML | 租户和 slot 隔离；行锁与条件更新；同 generation 内容冲突拒绝 |
| referral-service/db/migration：预计 V12（实施前复查占用） | 发布代次、运行 slot、激活/熔断审计及必要唯一约束；每张表与每个字段有中文注释 |
| referral-service/infrastructure：发布配置与许可适配器 | 明确开关、可信公钥及签名 KeyRing；缺配置不签 ACK、不发许可 |
| control：ReleaseApplicationService 与权益发布校验 | 从已审批图提取裂变权益和 SKU 固定版本；验证闭包后才可能解除对应拒绝分支 |
| marketing-contracts：必要合同说明和兼容性测试 | 优先复用现有类型；确需新增字段时版本化，不改变旧调用语义 |

### 校验与事务边界

1. 签名和载荷校验在写事务前完成；只接受受控配置中的公钥，禁止请求自带取钥 URL。首次写入前重验有效期。
2. 核对 REFERRAL_PLAN / marketing-referral-plan/1、载荷大小上限、字节摘要、tenant、环境、cell、namespace、定义和版本。活动映射需绑定可信定义与范围；编译载荷不包含 campaignId，禁止从请求凭空绑定。
3. 一次本地事务保存不可变代次及预热结果。同代次同内容重试返回一致结果，异内容冲突；数据库回滚不得产生可消费 READY。
4. READY 必须建立在完整制品和所需闭包验证之上。已有决策发布 gate 仅验证权益版本，不能替代裂变 SKU 版本证明。依赖未接通时明确 unavailable。
5. 激活只接受签名绑定已安装清单的指令。按 slot 行锁比较 activationSequence：旧序号不能覆盖；相同序号异内容拒绝；回滚使用更高序号选择保留制品。
6. 首期实现已明确的稳定版本路径；未实现的 canary 明确拒绝。若实际既有产品合同要求灰度，先补齐设计再开放。
7. KillSwitchDirective 本身没有过期时间，必须定义可信读取/订阅健康水位；不得因重放旧消息刷新为“最新”。缺新鲜依据返回 null。具体传输及水位合同需在接线阶段审查。
8. 许可只从数据库内已激活版本和可信活动映射产生，期限同时受 10 秒、清单/激活、活动及熔断水位限制。重启不能丢失 epoch，也不能把 WARMING/READY 当作 ACTIVE。
9. 指标记录验签拒绝类别、预热失败、ACK 状态、激活冲突和许可拒绝原因；不记录密钥、用户断言和主体明文。

## 验收矩阵

| ID | 可观察结果 | 验证 |
| --- | --- | --- |
| AC-01 | 正确签名和冻结计划可验证；篡改/错误 key/tenant/slot/ABI/版本均拒绝 | SPI 真实签名测试，逐字段反例 |
| AC-02 | 预热成功耐久化；同代次并发重试只有一个结果，冲突不覆盖 | 隔离 MySQL 并发、回滚、重启重读 |
| AC-03 | 缺审批、缺闭包或 SKU 版本不匹配不 stage/READY | control 与 runtime 拒绝路径集成测试 |
| AC-04 | ACK 绑定身份、代次、时间、ABI、buildDigest 和容量；不足阈值不激活 | control ACK 合同及阈值测试 |
| AC-05 | 乱序/重复/并发激活不会回退；回滚也递增 epoch | MySQL 锁和签名重放测试 |
| AC-06 | 未激活、过期、范围不符、熔断水位缺失时无许可；合法许可最多 10 秒 | 固定时钟边界及事务前后测试 |
| AC-07 | 应用重启可恢复发布事实；恢复不把旧取消奖励变有效 | 持久恢复及既有参与/授权回归 |
| AC-08 | 默认配置继续拒绝真实发布；旧 decision/journey 不回归 | 默认上下文和现有 control 专项测试 |

## 连续执行顺序

1. **R1 制品验证**：实现 AC-01，使用真实临时签名密钥测试，不需要外部身份业务决定。
2. **R2 持久预热与 ACK**：实现 AC-02/03 的 runtime 部分；先确定签名闭包来源，再允许 READY。数据库只在隔离测试容器迁移。
3. **R3 激活、熔断和许可**：实现 AC-05/06/07，完成可信活动映射和水位合同，保持未配置默认拒绝。
4. **R4 control 接线**：补权益/SKU 闭包及 ACK 阈值，完成 AC-03/04/08；全部依赖和测试满足才调整 REFERRAL 拒绝。
5. 每阶段代码审查、针对性测试与文档同步，按完整逻辑单元本地提交；完成一次相关全量回归。未满足的外部条件记录阻塞，不伪装通过。

营销仓库当前 `feat/referral-completion` 有后端及 Cursor 未提交改动。实施前记录差异，不提交或覆盖他人文件；新增能力依赖既有未提交基础时列出依赖，不能声称单独 cherry-pick 可构建。

## 验证、文档与 CI

- 测试入口：营销 Maven reactor 的 referral-runtime-spi、services/referral-service、services/marketing-control-service 及合同测试；实施前核对现有 Surefire/Testcontainers 配置。
- 使用隔离 MySQL，覆盖真实唯一约束、回滚和锁竞争。禁止对 dev_infra 共享业务库执行测试清理。
- 记录精确命令、测试数、失败与源版本；本次只完成代码对照，未运行新业务测试。
- 维护营销 `docs/REFERRAL_IMPLEMENTATION_STATUS.md`、发布运行说明、迁移说明、配置清单与对应 delivery 报告；同步交易合同状态，区分本地完成与外部验收。
- 沿用营销现有 CI 验证入口；新增测试纳入 reactor。确认流水线实际覆盖后才决定是否补 job；远端未运行不得声称 Actions 通过。

## 上线、回滚及待决项

本地验证后仍默认关闭，无共享迁移、部署或推送授权。生产密钥由 KMS/受控 KeyRing 提供，测试私钥不得用于环境配置。应用回滚关闭入口并保留代次、激活与业务审计表，不删除事实表或改写历史参与版本。

待明确的外部条件：KMS/JWKS/轮换；权威权益与 SKU 闭包读取；活动与定义映射；熔断健康水位传输；副本/容量策略。身份权威、maxBindAge、结算/首单政策和 BFF 继续沿用原待决项。R1 可先完成，后续不以假数据替代真实依赖。

## 审批状态

awaiting。用户已授权提交现有代码并进行下一步，本文完成下一步的具体方案；此前 T12/合同授权未明确批准这份跨仓库发布实现设计。待确认后从 R1 连续执行，无需逐阶段确认。
