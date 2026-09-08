# 可恢复任务租约设计

## 范围与依据

沿用已批准FINAL_PLAN第6／8节、DATABASE_DESIGN的tc_job_lease及TC-31／32。新增transaction-jobs，不添加调度中间件；支付只依赖core中的JobSchedulerPort，jobs实现该端口并独占自身Mapper。前端无变化，HTTP合同不增加。

本页记录V008队列及租约基础；后续已增加[密文回调接收](PAYMENT_CALLBACK_INBOX.md)与[支付恢复编排](PAYMENT_RECOVERY_WORKER.md)。真实渠道、风控、常驻调度、任务监控及终态补偿仍须继续实现。

## 流程与事务合同

1. PaymentService创建尝试时，经JobSchedulerPort.enqueue在同一本地事务追加PAYMENT_RECOVER任务。唯一键为tenant＋type＋businessKey，重放不重置历史任务。V008只回填迁移时已提交尝试；滚动升级必须等待旧版写入实例及在途事务全部退出，再调用PaymentRecoveryBackfillService.enqueuePage按全部租户从零游标扫描至返回原游标，完成幂等收尾补漏。扫描期间新版写入自行入队；旧版尚未退出时的一轮扫描不能作为完成证据。
2. claim只允许事务外调用，自己建立10秒READ_COMMITTED短事务，以MySQL UTC时间选择到期任务，FOR UPDATE SKIP LOCKED有界抢占；业务事务默认隔离不变。每次接管递增fencing_token和attempts，返回前提交租约，不带锁进入网络。
3. renew也只能从事务外调用；相同owner及token且尚未过期才能续期，过期不能自行复活。owner必须包含工作进程实例身份，不能所有实例共用固定名。
4. complete／retry／review必须参加调用方事务，先完成业务写入，再携带原租约完成CAS。失效令牌抛异常使全部业务写入回滚；不能捕获后提交业务，不能把任务完成放到独立事务。
5. 状态为READY／RUNNING／DONE／REVIEW。RUNNING的next_run_at与lease_until一致；READY的next_run_at为退避时间。生成列runnable将两种可调度状态映射为同一等值索引槽位，索引(tenant,type,runnable,next_run_at,id)支持有序到期扫描，避免跨status排序；终态不参与扫描。租约及退避时间只使用数据库时间，不依赖主机时钟一致；续租不会缩短现有有效期。
6. 租约只能约束本地提交；不能阻止失联旧进程已经发出的请求。渠道同号幂等、可靠不可支付屏障、真实资金耐久接收和唯一入账仍不可省。

任务只保存业务引用和白名单格式错误分类码，不保存支付密钥、回调原文或任意异常消息。不提供HTTP任意建任务、SQL执行或人工改成功接口。review不释放库存或退款额度，不等于资金失败。

## 文件与数据

- core/JobSchedulerPort、JobType：跨域同事务入队合同。
- jobs/JobLeaseService、model/JobLease、mapper/JobLeaseMapper及XML：抢占、续期、完成、退避、人工核对。
- V008__job_leases.sql：新增注释完整的tc_job_lease，租户唯一键、到期索引及状态一致性CHECK；V001～V007不改。
- PaymentService：新尝试同事务入队；FoundationTest：隔离MySQL故障／竞争验证。

## 验证计划

- JL-01：支付尝试与任务一起提交或回滚；同键重放只一任务。
- JL-02：多工作者并发领取无重复，跨租户隔离，批次和租约上限生效。
- JL-03：到期接管令牌单调递增，旧owner／token无法续期或完成。
- JL-04：业务写入后令牌失效，完成失败使业务／事件同事务回滚；合法完成仅一次。
- JL-05：延迟重试不提前领取；人工核对不自动领取，重放入队不重启终态。
- JL-06：数据库状态约束、时间源独立于应用Clock，禁止携带外层事务领取。
- JL-07：滚动升级收尾补齐缺失任务，分页幂等且不重启已完成任务。
- JL-08：到期查询执行计划使用idx_job_due，无filesort；锁住一任务时可领取其他到期任务。此测试不代替大规模容量测试。
- JL-09：完成和续租等待数据库行锁跨越失效点，解锁后均拒绝旧租约；不能以语句开始前的过期快照续活。

补漏每页强制从事务外调用，自有10秒READ_COMMITTED事务提交后返回；避免沿用旧RR快照或把全租户扫描放进同一个长事务。

## 后续Worker必须接入的约束

当前已有受控批次支付恢复Worker与指数抖动退避，无真实渠道或自动租户扫描。租户调度公平性、线程池预算、最大未决年龄告警和人工恢复命令待实现。支付结果先耐久接收，陈旧Worker观察到真实资金也不能丢弃证据，应由回调任务处理。完成租约只是本地任务状态，不能据此伪造支付成功。

V010补充[订单取消／过期持久唤醒](ORDER_RESOLUTION_PLAN.md)：独立ORDER_RESOLVE跟随订单生命周期，schedule／wake与业务同事务，运行中使用唤醒代数防旧退避覆盖，不重启旧PAYMENT_RECOVER的DONE。常驻公平调度仍待接入。

先隔离MySQL执行mvn verify及独立只读审查，成功后授权范围内迁移共享库并核验注释。当前设计不证明真实渠道交错、容量和完整TC-32；这些要求继续保留。

## 本切片实际验证

2026-09-07 build-job-final.log的mvn verify退出0，79项全通过（Foundation71＋其他8），含10项新增任务用例。V008共享迁移与validate退出0：28表／347字段中文注释完整，尝试／任务均0行。历史失败、修复和证据边界见VERIFICATION.md及delivery/transaction-center/QA_REPORT.md。

独立只读审查已复核；当前无自动Worker运行。后续迁移从V009开始，不修改V001～V008。
