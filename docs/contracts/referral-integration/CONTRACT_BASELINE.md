# 裂变身份、证据与发布接入基线 v1

2026-09-09。本文把现有代码合同与待实现适配明确分开，用于双方实现和审查；不是外部签收或开放真实流量的记录。对应营销C01/C02/C03及发布许可。冻结的是已经存在的字段与安全约束；权威来源、业务含义和新增wire格式未获得确认的部分保持待决，不替用户批准。

## 1. 调用责任与实施单元

| 单元 | 生产者/调用者 | 消费者/实现位置 | 本轮结论 |
| --- | --- | --- | --- |
| ID-01 用户登录 | Casdoor验证用户；实际BFF验证用户访问令牌 | BFF生成专用用户断言；referral验证机器身份与用户断言 | 现有Casdoor sub只是登录主体，尚无证据说明就是跨系统canonicalSubject |
| ID-02 主体/范围权威 | 需明确会员映射及组织/门店权威系统 | 交易AuthoritativeSubjectMappingPort；营销TrustedReferralSubjectPort | 不存在已确认的可调用映射端点；auth-platform主要提供SpiceDB判权，不能把判权接口当会员合并/注册来源 |
| EV-01 交易原生事实 | 交易EvidenceController/只读服务 | 营销适配器调用现有端点，保留字段和原生政策版本 | 路径与形状已核实，不能用旧C03目标覆盖已有同名路径 |
| EV-02 营销资格证据 | 映射/范围/政策确认后由适配器组装 | referral证据Inbox/history/current/quarantine/fanout | 原生事实不直接等于营销C02/C03 READY；缺映射/范围/政策一律保持PENDING |
| PUB-01 发布治理 | control审批、制品闭包、签名ReleaseManifest | referral runtime下载验签、预热、签名ACK | 复用现有ReleaseManifest/RuntimeAck类型；referral分支仍显式拒绝 |
| PUB-02 运行许可 | 已验证本地release、路由epoch和kill-switch | ReferralParticipationPermitPort | 短期许可最多10秒；无发布/熔断依据返回null，不从HTTP body生成许可 |

实施顺序：ID-02权威映射及ID-01签名边界 → EV-01客户端与EV-02保守转换 → PUB-01闭包/ACK → PUB-02许可；身份/证据适配与发布制品校验可并行开发，真实参加入口要等全部条件满足。每个单元应独立提交、合同测试及异常验证。

## 2. 身份字段与消费规则

机器访问令牌与`X-Subject-Assertion`分别校验。营销C01目标字段为：`iss,aud,sub,tenantId,organizationId,shopId,iat,exp,jti,method,path,bodyDigest`；专用aud=`marketing-referral`，有效期最多60秒。这些是既有设计约束，不表示当前已有HTTP参与入口。

| 输入 | 绑定与禁止事项 |
| --- | --- |
| issuer、audience、算法、kid | 固定受控配置及JWKS/KMS；算法和轮换/撤销方案待双方签收，不接受请求jku/x5u取钥 |
| sub/canonicalSubject | 必须来自权威映射，精确值比较，不trim/大小写转换；禁止由memberId或机器sub拼接产生 |
| tenant/org/shop | 机器允许范围、用户断言、活动范围三者交集；不得用X-Tenant-Id覆盖交易JWT tenant_id |
| method/path/bodyDigest | 绑定实际HTTP请求；现有RequestBinding的INTERNAL形状不是公开HTTP摘要协议 |
| jti/幂等键 | 断言消费与业务事实同事务；唯一作用域至少tenant+issuer+jti，并绑定operation、主体、原业务幂等键和稳定摘要 |
| iat/exp | 写事实前锁后重验；过期首次请求拒绝，原永久成功重放按可信原业务身份核验，不生成新业务结果 |
| 落库身份 | 分离版本HMAC索引和密文；索引/加密密钥分离，原始断言不落库、不进日志 |

JWT `tenant_id`在交易是正整数文本，营销TenantId为受限字符串；映射适配器必须显式验证可表示范围及同一租户，不默认把任意tenant-a转换成数字。org/shop空值/全店范围编码同样需签收，不能假造默认门店。

待决：canonicalSubject和范围权威系统、会员合并修订/生效时间、BFF服务身份与项目、签名算法/JWKS/KMS、规范JSON摘要版本及跨语言golden fixtures、允许时钟偏差、maxBindAge起点。maxBindAge未确认前继续使用受信绝对bindUntil并拒绝无法确定的首次绑定。

## 3. 证据端点：以实际代码为准

| 已有交易路径 | 已有请求 | 已有返回 |
| --- | --- | --- |
| GET `/internal/v1/orders/{orderId}/referral-evidence` | 本地订单内部ID | Result(orderId,status,evidence) |
| POST `/internal/v1/order-referral-evidence:query` | `{orderIds:[...]}`，1–100 | 每个请求项独立状态的批量结果；不是canonicalSubject首单查询 |
| POST `/internal/v1/customer-eligibility:query` | `{memberId,policyVersion,campaignStartAt?,evaluatedAt?}` | Result(status,evidence)；不是C02的canonicalSubject请求 |
| POST `/internal/v1/first-order-referral-evidence:query` | `{memberId,from,to,firstOrderPolicyVersion}` | FirstResult(state,firstConfirmedAt,evidence) |

以上需要交易机器JWT、`trade.evidence.read`及受信actor白名单，入口默认关闭；管理端额外scope查询不能替代服务对服务合同。

### C02转换清单

原生字段：`memberId,registeredAt,everPaid,firstPaidOrderNo,factVersion,eligible,reasonCodes,evaluatedAt,policyVersion,everPaidAtEvaluation,observedAt`。

- 先通过权威映射取得memberId和来源凭证，再查询原生接口；Mapped只证明主体到会员，不证明组织/门店或新客资格。
- 仅支持已批准原生政策`REGISTERED_DURING_CAMPAIGN_V1`、`NEVER_CONFIRMED_PAID_V1`；不能将其改名当作其他新客政策。
- `eligible`只有在政策匹配时才可解释为目标资格；`everPaid`是当前不可逆事实，`everPaidAtEvaluation`是指定时点评估，二者不能混用。
- `factVersion`与映射authority.revision分属不同版本空间；不得拼成单个伪customerRevision。目标schema若要求单修订，应先定义适配器持久版本/来源水位后实现。
- 只有来源明确NOT_FOUND才产生权威不存在；超时、无本地映射、MERGED未完成迁移保持PENDING/REVIEW，不默认为新客。

### C03转换清单

原生字段：`orderId,memberId,evidenceVersion,firstOrderPolicy,isFirstConfirmedPaidOrder,paidAmount,refundedAmount,pendingRefundAmount,netPaidAmount,conservativeEligibleAmount,currency,paidAt,completedAt,asOf`。

| C03目标 | 已有来源与限制 |
| --- | --- |
| canonicalSubject/org/shop | 原生订单证据不含这三项；必须权威关联，不从actor或展示名称补齐 |
| orderRevision | evidenceVersion是原生证据版本；映射/范围变动也能改变目标证据，需明确版本模型后再转换 |
| settledAt/state=SETTLED | paidAt不是商业结算；completedAt也不能未经业务批准就等同settledAt，不自动把支付成功改为SETTLED |
| firstOrderPolicyVersion/isFirstEligibleOrder | 原生是“首次确认已付”政策；并非任意“首笔有效/结算订单”政策；零元/全退/迟到时间不能重排首单 |
| 金额 | netPaidAmount=paidAmount-refundedAmount；conservativeEligibleAmount还考虑pendingRefundAmount，适配器不能丢掉待退款风险再发奖 |
| snapshotAt/evidenceId | asOf是本次原生读取水位，不是消息提交水位；需可信来源和版本绑定的证据ID，不能用请求traceId代替 |

推荐增量：保留原生DTO，通过专用版本的营销适配接口表达映射凭证、原生policy/version、待退款金额与资格状态；不兼容旧目标时升级目标schema，不修改已经上线的同名请求。此建议尚未签收，暂不生成冒充已开放的OpenAPI路径。

累计退款证据按订单版本合并；重复eventId同摘要重放、同revision异摘要隔离、旧revision不覆盖新快照。对账从权威查询补齐缺口；单次退款增量不能直接当累计事实。查询/转换不应创建奖励或批准付款。

## 4. 发布合同与状态推进

现有`ReleaseManifest`包含租户、environment/cell/runtime/namespace、generation/stable/canary/retained、artifacts/schemaVersions、activationAt/expiresAt、approvalCaseIds和signature。现有`RuntimeAck`包含manifestId/generation/runtimeId/cell、status、buildDigest、supportedAbis、warmedArtifactIds、capacity、acknowledgedAt及签名字段。

接线必须完成：

1. REFERRAL_POLICY审批记录、冻结活动类型/范围与规则；SKU/version及权益闭包由权威目录核验。
2. 编译制品`REFERRAL_PLAN`、ABI `marketing-referral-plan/1`与定义版本/sourceDigest/policyHash绑定，使用真实签名密钥。
3. runtime验证manifest签名、完整制品闭包、buildDigest/ABI和历史固定版本；DOWNLOADING/VERIFIED/WARMING不能发READY许可。
4. ACK经过服务身份、签名、slot/generation/runtime身份、时间与容量校验；满足批准的副本/容量规则才激活。
5. 激活后`Permit`冻结tenant/campaign/org/shop/definitionVersion/generation/artifactId/policyHash/routeEpoch和活动时段，issuedAt/expiresAt最多10秒，joinsAllowed受新参与kill-switch约束。
6. 暂停/熔断禁止新的参与或授权；恢复不能把已取消的奖励变回有效。原永久成功回放与新的派发许可分开判断。
7. 回滚选择已保留的可信制品和明确路由epoch；不覆写参与者冻结版本，也不以应用回滚删除授权/奖励/追回事实。

`ReleaseApplicationService`现有REFERRAL_RELEASE_NOT_AVAILABLE拒绝必须保留到以上条件有测试证据。不能只移除if判断或补一条假READY记录来宣称发布完成。

## 5. 必要合同测试与开放门禁

| 编号 | 测试 | 通过条件 |
| --- | --- | --- |
| ID-T1 | 真实签名及双身份、tenant/org/shop错配、DEV拒绝 | 非法输入无参与/绑定事实，日志无主体原文/断言 |
| ID-T2 | 并发jti、同键异内容、事务回滚、锁后过期、过期原成功回放 | 永久唯一结果与正确拒绝，失败不消费成功 |
| EV-T1 | 原生路径/DTO契约、未知字段、批量/首单区分 | 不复用错路径，不默默吞字段 |
| EV-T2 | 无映射/MERGED/超时、政策错配、支付未完成、待退款 | 不生成伪READY/SETTLED/资格通过 |
| EV-T3 | rev8退款先到rev7支付后到、同版异内容、查询补洞 | 累计不回退，冲突隔离，证据水位可解释 |
| PUB-T1 | 未审批、SKU过期、ABI不支持、错误签名/闭包 | 不stage/READY/activate |
| PUB-T2 | ACK重复/旧generation/错slot、熔断过期、恢复和回滚 | 只有当前许可可新参加，旧成功历史不改写 |
| X-T1 | 参加→绑定→证据→奖励授权→退款失效 | 真实沙箱全链路并对账，无重复发放或失效复活 |

本轮完成代码对照与实现合同基线，上表是后续适配验收，不标为已经通过。未解决的决策记录在[contract-status.json](contract-status.json)，不能把签收状态改成已接通。
