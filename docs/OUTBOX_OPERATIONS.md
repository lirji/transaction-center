# 后台发布队列与永久受控重放

> 时效说明（2026-09-08）：本文保留 V023 受控重放合同；后续 [V024 维护](OUTBOX_MAINTENANCE_OPERATIONS.md) 已实现可选的周期历史补漏，正文末尾“历史全租户补漏尚未完成”为当时范围。默认关闭，是否运行取决于部署配置。

代码已接入，验证状态以任务检查点为准。发布事件队列、异常任务队列和重放分别为：

- GET `/api/v1/trade/admin/outbox-publications?state=REVIEW&after=0&limit=50`：要求trade.admin，最多100条本租户元数据，状态PENDING/SENT/REVIEW；无payload。
- GET `/api/v1/trade/admin/outbox-publications/tasks?state=REVIEW&after=0&limit=50`：要求trade.admin，固定OUTBOX_PUBLISH类型，包含无事件的坏键任务；状态READY/RUNNING/DONE/REVIEW。规范UUID作为eventId，坏键仅referenceHash，不暴露任意业务键原文。
- POST `/api/v1/trade/admin/outbox-publications/{eventId}:replay`：同时要求trade.admin和trade.events.replay；Idempotency-Key请求头及expectedVersion、reason、ticketRef必填。返回首次eventId/version/replayCount，200仅代表已重新调度，不代表已发送。

租户和操作主体仅取已验签JWT。队列按id升序，以最后id继续分页；状态变化时从0重新刷新，这不是事件消费或历史一致快照。另一租户看不到事件和任务，也不能重放。发布开关关闭时仍可管理队列，工作者不领取或发送。

永久tc_outbox_replay_receipt先占唯一键再当前锁读，键为tenant+actorKey+requestKey；请求摘要包含原eventId、expectedVersion、reason和ticketRef。同键异内容409，已完成凭证永远返回首次结果，与可淘汰的tc_command独立。票据、事件状态、任务调度、审计和首次结果在同一事务中提交；故障全部回滚，不留下孤立成功凭证。工单引用是操作者提供的审计关联，不声称系统已创建或核验外部工单。

锁序固定receipt→Outbox→job。只允许匹配version的REVIEW事件和REVIEW发布任务，原payload须再次通过严格类型及身份目录校验。不会改写消息、换eventId或执行资金修复。SENT/PENDING/运行中不重置，非法消息仍需先修复代码/合同原因，不能通过重放绕过校验。

重放以原任务累计attempts作为Outbox.publish_attempt_base，任务fencing_token=attempts全局单调不回退。发布准备、失败和ACK处理都按当前锁读base拒绝旧轮租约，新轮按attempts-base重新计算十次自动尝试预算。失败历史和Broker事实不擦除；新轮次数、最后重放时间和永久凭证保留。

V023新增永久凭证表、Outbox三个轮次字段及两个后台分页索引，全部中文注释；不得修改已共享冻结V001～V022。审计由现有tc_operation_audit参与同一事务，记录可信主体摘要、原因、事件及前后版本。完整工单/告警渠道、坏键清理操作及历史全租户补漏调度尚未完成，不把三个接口视为全部运营或生产验收。
