# 正式裂变资格与订单证据 V1：实施约束

原合同：任务基线 `02-EXTERNAL_CONTRACTS_AND_API.md` 第3/5节、`03-FINAL_PLAN.md` 第5节及裂变 `07-EXTERNAL_CONTRACTS.md`。本文件拆分实现顺序，不取代原合同或删减资格、批量、事件、对账及生产要求。

下一切片先建立可验证的交易主库一致查询，再补完整 V1 事件与可靠发布。查询不是资格预占；营销仍须按原归因、身份合并、风控、奖励和冲正规则做最终判定。

## 查询事实与权限

会员策略严格区分 REGISTERED_DURING_CAMPAIGN_V1（注册时间在活动开始之后且不晚于评估时点）与 NEVER_CONFIRMED_PAID_V1（截至当前主库快照未发生正常有效支付）；不得按退款后净额把老客变回新客。请求的 evaluatedAt/asOf 不得假装支持历史回溯：仅能按不可变事实支持限定策略的时点投影，详见独立审查处理第3项；不能返回现在数据并盖过去时间。

订单证据必须含订单/会员、首单策略及是否首个本地确认支付、正常 paidAmount、成功 refundedAmount、在途 pendingRefundAmount、netPaidAmount、conservativeEligibleAmount、币种、paidAt、独立 completedAt、订单 evidenceVersion 与 asOf。异常收款及其补偿不能影响正常实收/首单/资格。零元完成订单仍无消费首单。

跨域查询应经专用只读 Port，由 server 查询编排在同数据源 REPEATABLE_READ 只读事务内组成一致快照；各域只访问自己的 Mapper/表，避免订单依赖支付而与支付→订单形成模块循环。单个与最多100个批量请求使用同一安全边界，批量每项给 FOUND/NOT_FOUND，不能泄露其他租户的存在性。不得用异步副本回答资格事实。

服务入口须 JWT issuer/audience/tenant 验证＋独立最小 scope＋可信服务身份配置，默认关闭。普通会员/admin 不能凭自己的 memberId 或 HTTP 字段创建可信服务身份。交易 memberId 与裂变 canonicalSubject 是两个层次；适配必须明确权威映射，未就绪/合并/不存在不能默认新客。正式路径和字段采用原基线，必要的映射在适配层保留双方签收版本。

## 版本与事件

订单当前 version 已由收款、退款预占/释放/成功、交付/完成推进；证据查询必须读取同一事务快照的订单版本与资金事实。退款事件带本次与累计金额、净额及 evidenceVersion，乱序消费者查询最新证据，不把旧值覆盖新值。

内部 v0 引用事件不能直接转发给营销。正式 envelope 保留 eventId、eventType、schemaVersion、tenantId、aggregateType/Id/Version、occurredAt、producer、traceId、data；数据字段按原合同。生产事务原子写完整不可变 payload，不能发布时按最新业务状态拼装历史事件。正常与异常退款的事件语义须区分，不能把异常补偿计为正常退款扣减。

发布器需要任务租约/fencing、Kafka 持久确认后推进、成功确认与本地状态间故障可重发、消费者 eventId 去重。开发基础设施复用 dev_infra kafka38，但 ACL/生产复制/配额/死信工单/受控重放仍须独立验收。必须提供最小/完整/错误 JSON fixtures 及乱序、重放、版本不回退、退款跌门槛和外层回滚测试。

## 后续完整范围

正式对账账单导入/完整性/差异分类/人工处置、真实渠道/审批/WMS/KMS联调之后，继续现有平台改造和完整裂变：邀请/身份归因、新客与首单、双边奖励/阶梯、退款追偿/冲正、风控及运营流程；前端交给 Cursor。全部原生产三高与故障演练验收保留，不以本地模拟替代。

## 独立审查后的明确处理

1. 原两个基线对同一个 POST `/internal/v1/order-referral-evidence:query` 分别定义 `orderIds` 批量与 `canonicalSubject/from/to/firstOrderPolicyVersion` 首单补查。实现保留两个不相交请求形状，严格拒绝混合字段；也可由明确配置的协议 adapter 分别映射。不能只实现批量而宣称补查已完成。第一切片交易原生事实 Port；后续营销 adapter 同时覆盖两形状及首单期间边界测试。
2. 身份映射由原注册的 tenant+issuer+subject 与权威身份服务的 canonicalSubject 映射提供，不能直接把任一 subject 字符串当全局canonicalSubject；必须持久保存映射来源/版本与合并修订。在权威映射接入前仅提供交易 memberId 合同，营销适配端保持 PENDING/不可用，不伪造 READY。organizationId/shopId 同样不得用 tenant/merchant 任意冒充，留显式范围映射。
3. 客户端时间无需等于服务器时钟。会员两个既定策略可利用不可变 registeredAt、firstConfirmedAt 做指定非未来时点的投影，若实现此投影必须返回 requested/evaluated 时点、当前读取水位及采用的事实版本，并测试注册前、首次确认前后边界和退款后历史不变；不能直接把当前 everPaid 盖为过去状态。尚无身份合并历史时合并主体不能 READY。订单金额只支持当前完整快照，不提供过去金额；snapshotAt 是本次读取水位说明，不冒充 MySQL MVCC 精确提交时间。
4. 快照 Port 仅普通一致读，不含 FOR UPDATE、REQUIRES_NEW、独立事务或不同数据源。只读事务也固定主库，未来读写路由不能将其送副本。批量用有界集合查询，避免100单×多个领域的N+1往返。
5. V1 退款相关证据统一按 ORDER 聚合及 evidenceVersion 排序，refundNo 在 data。除原必需主题外补充 `trade.order-evidence-changed.v1`：正常退款申请、拒绝/过期/可靠失败释放以及成功均通知证据变化；异常补偿不推进正常证据版本。通过金额/在途变化与版本/事件原子提交测试，避免奖励长期使用旧保守金额。
6. Trade V1 与裂变 C02/C03 的 envelope、状态（支付/商业完成/退款）、金额（gross/net/conservative）、身份/范围及策略版本必须有显式 adapter 与 golden fixtures。completedAt 才能作为独立商业完成证据，paidAt 不能随字段改名变成 settledAt。未取得双方签收及联调证据，仍记录为内部合同进展。
