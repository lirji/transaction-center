# 交易侧对账接口与操作

交易中心提供原始资金事实、可信差异受理和独立只查单。账单下载格式、匹配算法及最终封账仍由真实渠道合同和 recon-platform 负责。本阶段不自动改写资金、不自动结案、不直接退款。

| 入口 | 权限与开关 | 结果 |
| --- | --- | --- |
| GET /internal/v1/reconciliation/money-facts | trade.reconciliation.read；reconciliation.enabled；读取服务白名单 | 有界候选扫描后的渠道资金事实，200 |
| POST /internal/v1/reconciliation/reports | trade.reconciliation.report；report-enabled；独立报告白名单 | 永久来源事件受理，首次 OPEN，201 |
| GET /internal/v1/reconciliation/cases/{caseNo} | 财务读取 scope、开关、白名单 | 本租户单个差异详情及证据引用，200 |
| GET /api/v1/trade/admin/reconciliation-cases | trade.admin | 脱敏差异摘要，200 |
| POST /api/v1/trade/admin/reconciliation-cases/{caseNo}:query | trade.admin 与 trade.reconciliation.query；query-enabled | 永久首次排队结果，202 |
| GET /api/v1/trade/admin/reconciliation-operations | trade.admin | 当前持久执行状态，200 |

所有租户来自验签 JWT。报告 sourceId 为服务端固定配置，不能由正文覆盖。永久回执独立于可淘汰的 Commands；同键异内容冲突，不同来源事件的相同业务差异可以归并。新修订必须关联同来源、渠道、账单日期和行键的较早版本；旧证据不覆盖。

资金事实包含异常实收及成功退款，在途预占不作为资金发生。先扫描至多100个租户流水候选再过滤渠道，因此空页可能 hasMore=true；应按 nextCursor 继续。固定上界不是提交水位，各页不是同一个冻结快照。迟提交低 ID 需在新轮次从0补读，并按永久 ledgerNo 去重。

查询操作只接受 QUERY_PAYMENT 或 QUERY_REFUND，原引用从case取得。退款必须已有 submitted_at，且申请、资金、原渠道币种一致；不支持则拒绝受理并保持原状态。query-enabled 默认关闭；Worker开启后使用独立 RECON_QUERY 通道，线程计入全局16上限。默认该通道不发现租户、不领取任务。

操作状态：QUEUED → RUNNING → RESULT_ACCEPTED 或 REVIEW；不确定结果回QUEUED，最多五次转REVIEW。REPLAYABLE_ABSENCE直接待核对，不进入CREATE/补建/关单。RESULT_ACCEPTED只说明渠道证据已进入耐久接收路径，不能当作资金已处理、差异已解决。case保持PROCESSING；旧操作达到终态后，可携最新case版本提交新查询，保留全部旧操作。

永久POST重放始终返回首次QUEUED及原case版本。要查看当前进度，应读操作队列。工单引用并不表示系统已经创建或投递外部工单。真实生产渠道、证据签名、时区/账期/手续费、更正账单及外部确认仍需后续验证。
