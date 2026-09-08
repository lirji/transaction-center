# 交易侧对账差异受理

依据任务基线 `baseline/01-DATABASE_DESIGN.md` 第67行：recon-platform执行比对，交易中心tc_reconciliation_case只保存差异及处置审计关联。依赖当前原始资金事实导出，不在这里新增金额匹配算法。

## 受理模型

可信recon服务报告不等于资金执行授权。报告包含服务端配置的逻辑sourceId、来源事件ID、原channelId、statementDate、externalLineKey、revision、differenceType、可选本地业务引用、SHA-256证据摘要及不可变证据引用、可选priorCaseNo。差异类型包含长款、短款、金额、状态、手续费；修订保留原报告，不覆盖已存证据。来源ID由可信入口配置决定，不能接受普通客户声明自己属于recon。

V026计划建立tc_reconciliation_case（含基线公共字段、中文表字段注释）与永久tc_reconciliation_report_receipt。唯一来源键为tenant/source/event；同键不同正文拒绝。差异业务唯一键包含tenant/source/channel/date/line/revision/type，重复报告不同event仍只能关联同一差异；内容冲突拒绝，不覆盖。case_no为随机规范UUID。记录报告提交时的case初始结果，永久回执返回首次受理结果而不是之后可变处理状态。

受理短事务：先UPSERT并锁来源回执、校验摘要，然后校验本租户原渠道绑定及priorCase关联；UPSERT占差异业务唯一键、当前读核内容，再创建唯一case与审计、写永久回执。所有步骤同事务，审计失败整次回滚；不要先查空再插入造成RR间隙锁死锁。回执表避免引用case的外键在显式锁序之前隐式加锁。对相同case不同event的并发序列单向receipt→case，永久回执不依赖可淘汰Commands表。

业务引用必须携带固定类型（PAYMENT_ATTEMPT、REFUND、RECEIPT）；受理时通过各域Port核本租户及原channelId，不能把任意用户填的渠道交易号当本地事实。无本地匹配的长款可无引用进入待核对，不能借此直接建立真实收款。priorCase必须同tenant/source/channel/statementDate/line且revision较低，避免把不相关差异连接为更正链。

## 查询与后续动作

初始状态OPEN；租户管理员可按状态/id查询分页摘要和单个case。证据原文/凭据不进入列表；证据摘要和不可变引用的访问按可信财务权限另行约束。报告受理入口要求trade.reconciliation.report及独立可信服务白名单，默认关闭。

这一阶段不提供任意“标记资金成功”或人工字符串结案。后续处理命令分开建立永久幂等/版本和审计，逐动作绑定既有领域Port：原支付查单、原退款查单、异常收款补偿待审批申请。不能无条件重启REVIEW/DONE任务，不能把仅入队显示为完成处置。确定调用Port及租约行为后再实现安全调度；真实人工核对结案需专用可信证据合同，不能复用退款专属approvalRef字符串。

## 验收与剩余

真实数据库检验同事件多并发唯一受理、同事件异内容冲突、不同事件同case、业务键内容冲突、错租户/错渠道、修订链约束、审计失败原子回滚、永久回执不依赖Commands保留，以及真实HTTP scope/服务主体/开关。查询范围/索引和返回字段须经独立审查。渠道文件完整性、真实recon匹配与外部工单投递仍属于后续跨平台验收，不由内部受理测试代替。

## 实施审查决议

来源request_hash包含事件ID与content_hash，case的content_hash排除传输事件ID，但覆盖来源、全部业务字段、证据和prior引用。REFUND明确为支付域资金退款编号，所有归属查询由只读Port完成；不读取credentialRef、不调用prepare。prior不可变关联普通读，不锁旧case。报告入口独立report开关/白名单/sourceId配置，财务read授权不能报告；当前只建立OPEN并提供管理摘要，不执行资金动作。
