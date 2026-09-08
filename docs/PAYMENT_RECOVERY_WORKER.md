# 支付恢复编排与渠道适配合同

## 范围与状态

本切片实现原 FINAL_PLAN 6.2／6.3 中本地调用许可、事务外渠道策略执行、未知结果恢复及可信证据耐久交接。接口属于内部应用合同，无新增HTTP入口、真实渠道实现或默认模拟渠道。后续已补订单唤醒及默认关闭常驻调度；风险内部门禁见[风险合同](PAYMENT_RISK_GATE_PLAN.md)，实际供应商、验签、网络超时/配额/限流、生产观测仍是准入前置。

数据库仍V009，无新DDL；所有已有SQL保持所属Mapper/XML。使用策略模式隔离渠道差异，公开Port隔离任务与支付实现，事务分段编排保持本地一致性。

## 处理流程

1. PaymentRecoveryWorker逐个领取PAYMENT_RECOVER任务；每批1～100，租约30秒。
2. 10秒本地事务：PaymentRecoveryPort.prepareInTransaction锁订单／意图／尝试，生成RISK／CREATE／QUERY／CLOSE／DONE／REVIEW；随后同事务完成、转人工或fenceAndRenew，并在等待租约锁后重新检查CREATE许可。失效凭据或期限使本地许可变化回滚。RISK走事务外风险评估和同事务结果/fencing，决定后100ms重检，WAIT才指数退避；没有有效ALLOW不设置submitted_at。
3. 事务提交后，PaymentChannelPort.invoke按不可变channelCode选择固定注入的PaymentChannelAdapter。无实现返回UNAVAILABLE并退避，不尝试其他渠道。
4. CREATE／QUERY／CLOSE调用都使用同一请求号、冻结商户、金额与币种。超时／断连转UNKNOWN，不解释为支付失败。
5. 可信RECEIVED／CLOSED证据先经PaymentCallbackService独立持久接收；再由回调工作者处理资金。即使网络期间租约失效，真实证据仍须保存；旧工作者只能被拒绝修改任务，不能丢弃资金信息。
6. 恢复任务退避，直至后续准备阶段看到可靠本地终态才DONE；EVIDENCE_ACCEPTED不是已经入账。正常收款、异常资金和库存决策仍由支付领域处理。

## 同号补建与崩溃恢复

首次CREATE许可提交后可能进程退出，实际网络根本未发送。不能仅永久QUERY等待不存在的订单。

- Adapter默认supportsSafeCreateReplay=false。
- 只有供应商已经认证“原号稳定幂等、同参数重复创建不会重复扣款、可靠关闭后原号不可复活”的产品，才可启用此能力。
- QUERY可返回REPLAYABLE_ABSENCE，含义为经过可信查询确认可按原号重试创建；它不是关闭证明。
- Worker重新开启短事务，经prepareReplayInTransaction重新检查订单取消、到期、可靠终态、有效风险ALLOW与租约。仍可支付才返回原号CREATE；风险待定为RISK，拒绝为REVIEW，已取消／到期则CLOSE，本地终态则DONE／REVIEW。
- 每次租约最多执行一次补建，不递归无限调用。查询后的状态检查不能消除“检查完成后进程暂停”竞态，渠道原号幂等和关闭墓碑仍是最终约束。
- 未认证供应商的普通NOT_FOUND只能映射PENDING／UNKNOWN，不得伪造REPLAYABLE_ABSENCE或CLOSED。

## 扩展API与错误合同

| 边界 | 方法 | 关键要求 |
|---|---|---|
| PaymentRecoveryPort | prepareInTransaction／prepareReplayInTransaction | MANDATORY；调用后同事务fencing；无网络 |
| JobLeaseService | fenceAndRenew | MANDATORY；绑定租户／主键／owner／token／类型／业务引用；先等锁再判断数据库时间 |
| PaymentChannelAdapter | channelCode／supportsSafeCreateReplay／execute | 固定策略注册；重复渠道启动失败；整体超时参数10秒 |
| PaymentChannelPort | invoke | NEVER；UNKNOWN／PENDING／UNAVAILABLE只调度退避；EVIDENCE_ACCEPTED须已持久接收 |
| PaymentRecoveryWorker | runBatch／processLease | NEVER；业务许可短事务与网络分离；失效租约不覆盖新工作者 |

适配器将确定的超时／断连映射ChannelCallUncertainException，不携带原文／密钥／底层异常消息。其他程序异常不得被悄悄解释为资金失败；租约到期允许接管，持续调度还需接入。整体超时由实际适配器落实，本接口传入Duration不等于已证明网络超时有效，当前没有HTTP客户端实现。

RECEIVED金额／币种即使不匹配预期也必须保存真实证据，不能在网络层过滤真实异常资金。错渠道／商户／请求号拒绝进入本次接收，固定错误码退避；冲突报警和人工修复入口待实现。查询原始签名输入／证书链、稳定事件键与原文保留范围需要各供应商单独确定，不能随意重新序列化或随机生成事件去重键。

默认没有可执行真实适配器。启用真实CREATE前必须完成风控拒绝／超时fail-closed、凭据托管、产品可靠关闭合同认证、网络超时／限流／隔离及观测，不能仅注册一个实现就宣称可上线。

## 验收与证据

实际最终结果：build-recovery-release.log 对应 mvn verify 退出0，109项全部通过（Foundation92＋其他17），0失败／错误／跳过。原始8份报告及失败历史位于 .codex/tasks/referral-end-to-end/evidence/payment-recovery/。本轮无数据库迁移，共享库仍V009；本地测试不替代真实渠道验收。

新增集成用例针对：

- 网络阶段无事务、无数据源连接绑定；CREATE未知后按同号QUERY，非到期任务不提前重试。
- 证据先耐久、资金后处理；处理后恢复任务终态不再调用渠道。
- 旧租约不能提交首次许可或发起网络；网络期间接管后证据仍保存，新owner不被覆盖。
- CLOSE普通PENDING保留订单CLOSING，只有耐久可靠证明处理后才取消。
- 缺适配器安全等待；公开Worker代理拒绝外层事务。
- 许可提交但未发请求的崩溃恢复：QUERY→原号CREATE。
- QUERY期间取消：补建前重检转换为CLOSE。

独立只读审查发现并推动修复“永远查询不存在尝试”的P1活性缺口；审查没有运行测试。新增协议单元测试覆盖重复策略、无证据成功、未认证补建、错请求证据、异常金额保留、接收失败不应答和未知异常分类。实际构建结果需以最新交付记录及原始报告为准，不以本计划文字代替通过证据。

## 未完成项与后续迁移

后续V010已补[订单级任务及持久唤醒](ORDER_RESOLUTION_PLAN.md)，解决旧PAYMENT_RECOVER已DONE后取消和无支付尝试过期订单，不重置旧DONE任务。常驻租户公平调度、停机、指标告警、风险决策、真实供应商与KMS仍待完成。本文109项是历史恢复切片证据，最新全量结果见VERIFICATION.md；退款、履约、正式消息及完整裂变目标不变。
