# 常驻Worker设计与运行合同

状态：内部实现通过最终136项本地回归，V011共享迁移与validate成功。WorkerRuntime调用现有PaymentRecoveryWorker、PaymentCallbackWorker和OrderResolutionWorker；默认不创建运行时Bean。deploy/compose.yaml中API固定禁用，worker为显式profile，未部署或启用共享后台处理。

## 边界

- 默认关闭，通过显式角色／配置启用；API启动不能自动处理共享业务任务。复用dev_infra与现有MySQL租约，不新增调度中间件。
- 三类执行通道分别限制并发，回调处理不应被慢渠道关闭／查询占满；每租户每轮任务数有界，任务领取仍逐个执行。
- 不保存每个任务为内存定时器；数据库next_run_at、租约与唤醒代数才是权威调度状态。
- 租户发现只读jobs自己的Mapper。使用有界租户游标轮转，保留全租户覆盖；不得跨域读会员／订单Mapper，不把固定测试租户清单当生产发现机制。
- 检查实际EXPLAIN，避免从所有任务无索引扫描筛选少量到期租户；如确需索引，使用V011增量迁移，不修改V010。
- 有界线程池及队列；只有有执行容量时才领取任务，拒绝时不预占租约。多个进程共享SKIP LOCKED/fencing，而非依赖进程锁实现互斥。
- 工作循环捕获单任务异常后记录脱敏分类并继续后续租户；数据库不可用退避，不能无限紧循环。未知资金仍保留，不把多次失败自动变失败终态。
- 停机先停止发现／领取，给予有界在途收尾；超时后的租约留待新实例接管。线程中断不能证明远端未执行，仍沿用渠道幂等与耐久证据规则。

## 配置与实现

`WorkerRuntimeConfiguration`固定注册三类Batch策略，每批调用原Worker的`runBatch(tenant,owner,1)`。各类独立固定线程池与游标，每池只提交固定数量的常驻循环，队列与线程数同上限，不为每个任务分配内存计时器。所有业务锁、租约、UNKNOWN及终态语义保持原Worker合同。

| 环境变量 | 默认 | 限制／含义 |
|---|---|---|
| TRADE_WORKER_ENABLED | false | 显式true才创建和自动启动Bean |
| TRADE_WORKER_CALLBACK_THREADS | 2 | 每类1～8，三类总和不超过16 |
| TRADE_WORKER_PAYMENT_THREADS | 1 | 支付恢复独立容量 |
| TRADE_WORKER_ORDER_THREADS | 1 | 订单解析独立容量 |
| TRADE_WORKER_IDLE_MILLIS | 250 | 50～60000毫秒，轮转无完成工作时空闲 |
| TRADE_WORKER_SHUTDOWN_MILLIS | 30000 | 100～30000毫秒，全部通道合计等待预算 |

`JobTenantDiscovery.next(type,after)`是内部全租户只读入口，无HTTP映射；显式NEVER边界内开启自己的10秒RC短事务，返回前释放连接。唯一SQL在`JobLeaseMapper.xml`，索引`idx_job_tenant_rotation(job_type,runnable,tenant_id)`通过V011新增，按游标跳过整个租户、LIMIT 1，无OFFSET／全租户集合驻留。租户发现包括未来任务与有效租约，实际领取再按数据库时间检查到期，DONE／REVIEW不参与。

每次业务处理后继续游标，空探测每64次让出1毫秒；绕回时比较该通道自上次绕回以来累计处理数，无新增完成且当前也未领取才等待空闲间隔。不能仅凭绕回首租户为空就睡眠。多槽位共享计数与游标，在途未完成不计工作，迟到完成将在后续轮转抑制空闲等待；这不是全通道轮次屏障或严格延迟保证。大量未来租户仍消耗数据库探测，尚无全局SQL QPS上限或生产容量证据。

临时RuntimeException使用固定脱敏日志，连续失败按1／2／4／8／16／30秒退避；一次正常轮询后清零。不会因失败次数耗尽把资金置失败，也不重启DONE／REVIEW。指标不能替代待办告警。

停机先把接收标记置false，唤醒空闲线程，等待已进入的单任务批次；发现返回后再次检查标记。标记检查与进入Batch之间不是数据库原子屏障，已接收的批次可能在停机期间完成一次领取，属于在途收尾。总预算耗尽后请求线程中断，不能强杀不响应中断的网络调用；旧线程未退出时禁止重新start扩增容量。`isRunning=false`仅表示不接受新循环，不证明网络已结束。资金证据、提交与接管仍受原耐久／fencing规则约束。

## 部署与运维边界

在完成迁移、凭据和渠道准入后，可显式执行`docker compose -f deploy/compose.yaml --profile worker up -d trade-worker`。这是运行说明，本次未执行。worker复用同一构建及dev-infra网络，不发布宿主端口、容器内只监听回环，Compose给予60秒退出预算；普通API固定禁用后台。当前镜像仍包含Web应用，不声称已拆为无HTTP的独立制品。恢复旧镜像时先停worker，保留V011附加索引，不回滚历史DDL。

Micrometer仅按固定`job_type`标签暴露进程内`trade.worker.polls/handled/errors/elapsed/active`与全局`running`：polls为调用处理器的轮询次数，handled为批次返回任务数而非支付成功数；elapsed包含发现与业务执行累计秒数，不含空闲等待。默认管理端点仍只开放health，尚未配置Prometheus采集与报警，不为可观测性直接开放未经授权的端点。

## 运维待落实

队列年龄、耗时分位数、固定失败分类、租约接管计数、采集／告警与容量配置仍待补齐。当前最大16工作线程不等于10连接池能承载任意配置，需结合API隔离、查询量与真实网络时限压测选值；不能靠盲目扩池掩盖泄漏。REVIEW人工恢复仍需业务流程，不能靠自动重启DONE/REVIEW掩盖问题。

真实支付适配器上线前还需风控fail-closed、可靠关单认证、整体网络超时及凭据托管。运行内部Worker不等于获准真实收款。

## 验收

1. 默认禁用时无后台领取；启用时所有已存在任务类型自动推进。
2. 多租户轮转、一热租户不永久饿死其他租户；慢网络不阻塞回调通道。
3. 并发实例不重复提交资金副作用；实例退出后租约到期恢复。
4. 单任务异常／临时数据库异常不会永久停止循环，也不空转洪泛。
5. 停机不再接收新的轮询批次，已接收批次有界收尾；在途提交与迟到证据遵循既有fencing和耐久协议。
6. 使用隔离MySQL及可控制执行器验证，不在共享dev_infra故障注入。远程CI、三高长稳与生产容灾仍需独立证据。

最终`mvn -q -l build-worker-runtime-release.log verify`退出0：Foundation109＋WorkerRuntime10＋其他17，共136项，0失败/错误/跳过。新增3项集成用例验证租户发现/终态排除/未来任务不早领、真实XML的EXPLAIN使用range索引且无filesort、运行时与真实租约提交联动。联动用例只处理其独立租户的测试Batch，不冒充全局多进程真实支付网络验收。10项运行时测试覆盖配置默认关闭/显式启用与指标、热租户公平、未来租户探测及持续热租户吞吐回归、通道阻塞隔离、异常恢复、停机收尾/超时/发现期间停止和配置界限。

原始9份报告及共享迁移结果在`.codex/tasks/referral-end-to-end/evidence/worker-runtime/`。共享V011只增加带中文注释索引；29表/374字段注释缺失0，订单/任务0行。Compose带worker profile的config --quiet校验退出0，未部署。此前中间135项通过，最后整轮空闲修复后重新136项通过，不沿用旧测试。

之后继续退款／履约／正式事件／隐私／完整门禁及交易完成后的裂变改造，不以本切片替代全范围。


## 异常收款补偿通道（V019）

新增REFUND_COMPENSATE固定类型，运行时现有六个独立通道。TRADE_WORKER_COMPENSATION_THREADS默认1（1～8），六通道总线程仍不超过16；缺省不启用Worker，API角色固定关闭。异常真实收款与本任务同事务；处理只建立唯一待审批补偿，DONE不是退款成功。租约失效回滚申请，已有资金处置冲突进入REVIEW，瞬态数据库错误原任务退避重试。

历史数据先由V019补初始任务，滚动升级后还须所有旧写入及在途事务退出，再按所有租户从0调用PaymentCompensationBackfillService.enqueuePage至游标不变；重复扫描不重启DONE/REVIEW。详细业务和验证边界见REFUND_COMPENSATION.md。当前全量验证结果以最新任务检查点为准，不沿用历史运行时数量或早期测试数量。
