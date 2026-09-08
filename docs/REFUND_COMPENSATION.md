# 异常收款补偿接续

## 原要求与现有能力

依据原FINAL_PLAN 6.2/6.4与TC14/17：每笔真实资金保留，多付或关单后矛盾成功不得二次履约，进入唯一原路补偿；异常退款必须审批，UNKNOWN不释放。PaymentRefundPort已支持异常来源资金预占和成功记账，RefundService尚仅提供正常本人申请。

## 本地应用实现约束

补偿以可信租户和永久收款编号定位事实，金额及币种均取收款，不由调用者指定。只允许EXCESS、AFTER_TERMINAL、AFTER_CLOSE、AMOUNT_MISMATCH；NORMAL拒绝。按收款建立唯一补偿关联，不受申请主体、HTTP幂等保留期或重试次数影响。系统固定申请身份，仅内部编排可调用；必须经现有可信人工审批入口，不能开放普通admin直接批准。

每笔异常收款独立EXCESS组件键（按receipt主键），预算上限等于该实收；绝不能占GOODS/FREIGHT/TAX预算。使用既有资金Port、审批、原路冻结凭据、REFUND_RECOVER、Inbox和账本链路。补偿成功/释放不调用正常订单recordRefundChange，不改变正常净支付、首单、库存或履约状态。无正常收款的关单后资金也应可补偿。

订单→支付意图/收款→退款应用/组件锁序保持；创建申请、资金hold、组件、审计、Outbox与任务调度同事务。唯一收款关联永久保留，拒绝/过期/可靠失败不能由重放自动重建或绕过原审批；后续受控重新处置另有证据和授权，不直接改资金终态。

新增迁移从V018开始，仅扩展EXCESS组件约束及永久补偿关联，更新正常实收专用旧字段注释为真实实收语义，不修改V001～V017。数据扫描通过payment公开的有界游标Port供jobs调度，跨域禁止直接Mapper查询；无自动发现前不能声称TC14/17完整通过。

## 验证与完成边界

验证同收款并发与100次重放只有一个申请/hold/审批任务；跨租户/正常来源拒绝；强制审批不受高免审额度影响；补偿成功一次账本且正常净消费、首单、库存不变；无正常收款的异常来源、不同币种按原渠道原币退款；拒绝/过期释放、UNKNOWN预占及fencing回滚。生产还必须真实渠道联调、报警与受控异常修复，内部用例不能替代。

当前已实现内部补偿应用与V018，并通过修复后的专项；V019原子任务、独立工作通道与补漏也已实现，最终全量256项通过，0失败/错误/跳过；evidence/compensation含原始报告、独立审查及共享V019迁移/校验证据。

## 独立设计审查补充

V018在退款聚合增加可空compensation_receipt_id及唯一(tenant_id,compensation_receipt_id)，CHECK要求非空时等于实际receipt_id且approval_ref非空。先按PaymentRefundPort锁来源，再INSERT占唯一补偿关联，最后当前读；禁止对缺失关联先FOR UPDATE（RR间隙锁死锁）。重放先返回原聚合，不先校验可用余额或现行政策，不依赖系统Actor永不变更。不得先锁退款聚合再取来源锁。正常申请normalize继续禁止EXCESS，预算与item约束同步扩展且数量为零。


## 自动补偿与升级

PaymentObservationService将异常实收、账本、Outbox及REFUND_COMPENSATE同事务保存。RefundCompensationWorker在本地事务建立补偿并在末尾完成租约fencing；失租或数据库错误不提交半个申请。DONE只表示申请存在，后续仍待可信审批、REFUND_RECOVER原路渠道及Inbox确认。已有资金处置冲突进入REVIEW，不释放其他hold或重建补偿。

运行时新增第六独立通道，TRADE_WORKER_COMPENSATION_THREADS默认1，所有通道合计仍最多16，默认API不启动Worker；显式worker profile才运行。指标仅固定job_type分类，无收款标识。Compose静态校验不等于部署。

V019为历史异常收款初始补任务。滚动升级旧写入者及其在途事务完全退出后，必须按所有租户执行PaymentCompensationBackfillService.enqueuePage，从0分页至返回游标不再变化；每页最多100笔收款，正常行也推进游标。重复补漏不重启DONE/REVIEW。单次迁移或一次游标扫描不能证明并发旧写入无遗漏。
