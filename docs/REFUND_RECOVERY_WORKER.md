# 退款主动恢复合同（V016）

## 范围与持久化边界

退款恢复与回调消费分离。RefundService拥有申请/审批及本地执行许可，RefundRecoveryWorker通过公开Port编排，RefundChannelService负责固定渠道策略和事务外网络；SQL仅在强类型Mapper XML及Flyway迁移。没有新增HTTP入口，不接受客户端声明退款成功。

V016扩展任务白名单并初始化已有非终态申请任务，不新增表。V001～V015不重写。共享迁移是否执行以CODEX_PROGRESS和迁移原报告为准；隔离测试库迁移不能作为共享执行证据。

## 状态与流程

| 本地申请状态 | 恢复动作 | 资金约束 |
|---|---|---|
| PENDING_APPROVAL且未到期 | 预约到期；批准会持久唤醒 | 保留预占，不访问渠道 |
| PENDING_APPROVAL且已到期 | EXPIRED并完成任务 | 同事务释放一次 |
| READY | 本地SUBMITTED许可与租约校验同事务，提交后CREATE | 保留原渠道、原退款号及原金额 |
| SUBMITTED | QUERY；仅认证的确定不存在结果允许重新获取许可后同号CREATE | UNKNOWN、PENDING、不可用均不释放 |
| SUCCEEDED/FAILED/REJECTED/EXPIRED | 完成任务 | 不重复资金副作用 |

申请与任务预约同事务；审批、成功和可靠失败与唤醒同事务。工作者每次领取一条、租约30秒，本地事务超时10秒。准备许可、审批过期处理、任务完成/续租均受末尾fencing保护，失效租约使整个本地事务回滚。网络调用在事务外。

QUERY返回REPLAYABLE_ABSENCE必须由明确支持永久同号去重且终态不可复活的适配器提供；普通查不到、超时或UNKNOWN不符合该条件。补发前重新读取本地终态/批准并校验租约，防回调先完成后再次派发。每次恢复最多补发一次，不更换商户退款号。

渠道资金证据先写入加密Inbox。即使随后租约丢失，已持久证据也不丢弃；RefundCallbackWorker负责最终资金、组件、订单事实及任务fencing的原子提交。恢复任务的RETRY/REVIEW不代表退款失败，也不能释放在途额度。

## 滚动升级与运行

RefundRecoveryBackfillService.enqueuePage逐租户按主键分页，每页1～100条、READ_COMMITTED短事务，禁止外层事务。旧版本写入实例全部退出后必须从零收尾扫描，返回游标未前进则本轮结束。它是内部运维能力，不是匿名HTTP接口，也未宣称已自动调度。

PENDING_APPROVAL按审批期限预约；READY/SUBMITTED使用持久wake，而不是只enqueue。否则旧实例在V016迁移后批准了申请，已有未来任务可能仍等到原审批期限。wake保留运行中的唤醒代数，不重启DONE/REVIEW；REVIEW需要有审计的人工处理，不能无条件重置。

第五独立运行通道由TRADE_WORKER_REFUND_RECOVERY_THREADS配置，默认1；全部五通道各1～8，总和不超16，运行时仍默认关闭。未知结果指数抖动退避，上限5分钟；审批等待也最多5分钟重检。生产吞吐能力需另行压测，线程配置不代表三高验收。

## 验收与限制

FoundationTest的refundRecovery开头用例覆盖首次派发、UNKNOWN同号查询、发网前宕机、认证/未认证补发、回调与查询交错、旧租约、迟到证据、审批过期、运行中唤醒、事务回滚、补漏幂等及旧实例审批后未来任务唤醒。2026-09-07全量`mvn -q -l build-refund-recovery-final.log verify`退出0，217项全部通过（Foundation175、RefundChannel11、其他31），0失败/错误/跳过；11份原始报告在`.codex/tasks/referral-end-to-end/evidence/refund-recovery/`。共享V016已执行（exec86670退出0），16版validate成功；35张业务表/485字段中文注释缺失0、恢复任务0条。后续新增迁移V017，不重写已执行版本。

真实供应商适配器、原渠道凭据公开Port、强制网络整体超时、可信审批后台、生产配额/KMS/告警/容量与容灾验收仍未完成。当前没有实际退款适配器；不可用时保留额度并重试，不模拟退款成功。本切片不完成异常多付补偿、实物退货、正式净支付事件或完整裂变。
