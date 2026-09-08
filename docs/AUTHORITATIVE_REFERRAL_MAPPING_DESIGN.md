# C02/C03权威映射衔接（待接口所有者确认）

2026-09-08源码复核：交易中心已有MemberTradingPort、CustomerEligibilityService、OrderReferralEvidenceService及订单归因快照。当前原平台服务源码搜索未找到可复用的canonicalSubject权威解析实现。身份平台提供认证不自动意味着其拥有会员合并事实；不把actor.key/memberId/租户ID改名为canonicalSubject/organizationId/shopId。

本文件是后续实现边界及外部待确认清单，不是已上线或双方签收合同，也不改写冻结的161项要求。原合同见baseline/07-EXTERNAL_CONTRACTS的C01～C03及已有CUSTOMER_ELIGIBILITY、ORDER_REFERRAL_EVIDENCE。

## 可信来源与持久事实

需要接口所有者提供会员主体映射的权威服务身份、稳定canonicalSubject、memberId关联、revision、effectiveAt及合并事件；商户/店铺范围需要其权威服务提供organizationId/shopId及版本。来源服务的配置与允许租户必须从受控部署配置获得，不能从业务body、普通用户JWT或分析measurement补齐。

后续接收器须以固定issuer、专用audience及允许actor为信任边界，在事务外验签，以source+eventId保存不可变Inbox和内容摘要；同revision同内容回放，不同内容隔离，旧revision不覆盖。未确认来源时入口默认关闭，解析返回PENDING且不授予资格。未知主体的NOT_FOUND不能解释为新客，权威合并返回MERGED并暂停相关奖励处理。

映射采用追加式历史，记录必要的最小来源审计引用、版本和有效时间；个人数据的保存期限仍待治理确认；撤销/合并通过追加新事实表达，不删除资金及身份审计证据。实际个人数据留存期限与注销展示仍待业务答复，本设计不授权清理或不可逆匿名化。

## 交易证据拼装

C02以认证租户+canonicalSubject解析权威本地会员后，调用既有原生策略Port；不重新实现首付/注册资格。请求policyCode/version/asOf与活动开始时点必须满足已冻结策略合同；不支持的策略明确拒绝，不能以默认策略替代。

C03的主体、组织/店铺范围须来自已确认且版本化的权威映射。订单金额/退款累计/首单使用原生同快照事实；交易原生策略保持FIRST_CONFIRMED_PAID_ORDER_V1，退款不重新分配首单；C03外部firstOrderPolicyVersion的名称与语义须显式适配并等待双方签收，不能直接把本地版本作为外部已同意的口径。跨库映射不能假称与订单MVCC原子一致，应持久化本地权威映射版本后同库读取或明确PENDING。

订单归因V032是营销授权的冻结引用，不代表已达到奖励门槛；不能依靠它补造canonicalSubject或店铺关系。历史订单是否允许补映射、一个租户多店下单如何选择范围，以及事实生效前订单如何呈现，需要真实权威范围合同后确定；未决时不发布伪完整C03。

## 下一实施切片与验证

1. 核对原平台真实身份/会员/商户接口及所有者，冻结映射输入与合并语义，保留上述待确认项。
2. 实现默认关闭的可信事实接收、追加式历史与最小审计凭证（期限待确认）/冲突隔离及查询Port；DDL使用下一未占用迁移号，表和每字段中文注释。
3. 接C02/C03只读适配和按主体首单补查；跨模块用Port，按原主库快照及有限批量读取，不引入逐条网络查询。
4. 测试租户/主体隔离、迟到及乱序revision、同revision内容冲突、合并暂停、查询缺失失败关闭、跨店错配拒绝、原生首单/退款事实一致及脱敏HTTP。
5. 独立审查、专项验证、机器合同与文档同步；真实来源、消费者合同及生产签收仍单独验收。

前端由Cursor实施。营销referral-service、规则发布、双边/阶梯奖励及取消/追回复用原平台，不以本文件或交易侧映射完成替代完整裂变交付。

## 既有约束继续适用

本补充与[IDENTITY_SCOPE_MAPPING_DESIGN.md](IDENTITY_SCOPE_MAPPING_DESIGN.md)联合阅读，尤其第4～6条：canonicalSubject密文保存、独立版本HMAC索引、密钥分离与轮换冲突检查；证据身份包含映射修订和原生事实版本；paidAt不等同settledAt，结算时间、金额、状态及正数revision通过显式适配与golden fixtures验收。不能只凭本补充跳过已冻结的隐私和一致性要求。

## 现有HTTP兼容性

EvidenceController已开放原生memberId/orderIds/first-order只读形状，均经trade.evidence.read和EvidenceAccess允许主体校验；旧说明中“尚无HTTP”是历史阶段状态。目标C02/C03部分路径与这些原生形状重合，实施前必须确定显式版本/独立适配路径，保留原生消费者兼容，不在同一请求中混用memberId和canonicalSubject，也不静默改变原响应结算含义。机器合同以docs/openapi.json和实际控制器为准。

## 2026-09-08 可独立实现边界

已新增内部只读主体Mapping Port、默认Unavailable实现及C02/C03就绪检查adapter；缺省PENDING，无READY构造、接收器、持久化或HTTP变化。权威缺失、合并、映射有效时间与原生策略待签收明确区分，详见[实现与验证记录](AUTHORITATIVE_MAPPING_BOUNDARY_IMPLEMENTATION.md)。本步骤没有实施上文待确认的来源接入、写入、范围映射或生产验收，下一步仍须先取得其权威合同。
