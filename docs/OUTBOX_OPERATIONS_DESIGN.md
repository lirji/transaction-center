# 发布隔离队列与受控重放设计

原要求来源：02-EXTERNAL_CONTRACTS_AND_API.md事件合同要求死信告警、工单、修复和受控重放；09-PRODUCTION_DESIGN.md要求权限、原因、审计并保留原eventId。当前REVIEW只完成持久隔离，不代表运营闭环。下一阶段补交易内部运营能力，外部工单/告警系统实际联调仍单列待验，不通过发送未经授权的消息冒充完成。

后台分页队列只返回租户内事件身份、聚合、版本、状态、失败分类/首尾时间/次数和ACK位置，不直接返回payload。计划GET /api/v1/trade/admin/outbox-publications（状态+游标，最多100）。读取延用已验证后台身份，状态枚举拒绝自由SQL或任意过滤。

计划POST /api/v1/trade/admin/outbox-publications/{eventId}:replay，要求独立trade.events.replay权限、reason、ticketRef、expectedVersion以及永久幂等键。身份及租户仅从JWT取得。此操作只重新投递原消息，不执行退款、改写payload或构造新事件ID。仅REVIEW可以重放，严格目录复核载荷；SENT/PENDING/运行中不重置。没有可信身份不能开放此入口。原合同对消息重放要求权限/原因/审计，未要求资金审批；不能任意增加退款审批才能重发消息的业务限制。

锁序仍Outbox→job。需要新V023（共享V022完成后再实现），为Outbox记录本轮起始attempt基数与重放次数，并建立永久tc_outbox_replay_receipt（tenant+actorKey+requestKey唯一，保存requestHash、eventId、首次结果/版本/重放轮次）；现有Commands可淘汰，只能辅助执行，不能作为永久凭证。先占凭证唯一键再当前读，内容冲突拒绝，已完成凭证直接返回首次结果。随后锁序为receipt→Outbox→job；任务fencing_token=attempts保持全局单调，不能重置为0来恢复十次预算。JobSchedulerPort新增仅REVIEW→READY的受控方法，当前锁读返回原attempts；Outbox记录新基数后，工作者在准备和失败处理各自当前锁读后按attempts-base计算本轮重试上限；差值<=0表示旧轮租约，必须判失效，不能将新轮倒退到REVIEW。状态、基数、审计、幂等结果和重新调度同事务，任何失败全部回滚。重复同命令不重复增加轮次或复活已经完成任务；不同命令并发以expectedVersion拒绝过期请求。

无效长键任务没有对应Outbox，必须另在任务隔离列表可见，不能以事件队列覆盖全部毒任务。下一步定义只读任务分类统计/列表，以及有界全租户发现和历史V1补漏调度，保留完成水位证据。历史补漏不能复活REVIEW或DONE，与人工重放职责分开。

关键验证：跨租户/缺权限拒绝；两次并发重放只有一次成功；同命令重放原结果；锁序、审计或入队失败回滚；原eventId/payload不变；全局fencing不回退而新一轮重试预算可用；旧租约无法完成新轮次；SENT不允许再发。低基数指标覆盖队列最老年龄/REVIEW数/失租及发布失败，并提供告警规则；真正触发外部告警/工单需已授权接入后验收。
