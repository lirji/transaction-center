# V010 订单取消与过期持久唤醒

状态：内部实现已写入，完整回归与共享迁移结果见最新VERIFICATION.md；不得仅据本文断言已上线。依据原 FINAL_PLAN 6.3、TC-15／16／32，不改已执行V001～V009。

## 本切片修复的原缺口

- OrderService.cancel原先只更新订单／释放无支付许可库存／写内部Outbox；有支付许可时进入CLOSING，没有持久唤醒新任务。
- PAYMENT_RECOVER按尝试唯一。尝试可靠关闭且订单尚可重新选择渠道时，任务可以DONE；稍后取消不能通过重复enqueue重启DONE。
- 从未创建支付尝试的过期订单也没有PAYMENT_RECOVER可领取，不能仅遍历支付尝试补漏。
- 当前runBatch仍需外部持续调用，没有生产常驻调度证明。

## 实现边界

1. 新增以orderNo为业务键的ORDER_RESOLVE任务，其生命周期与订单绑定；只有订单PAID／CANCELLED等无需支付关闭的终态才完成。不能以某次支付尝试CLOSED替代订单终态。
2. 正金额订单创建时同事务预约到expiresAt；用户取消进入CLOSING时同事务唤醒原订单任务。零金额已支付订单不创建无意义过期任务。
3. Payment公开订单解析Port读取自身意图／尝试，通过OrderPaymentPort检查订单：无任何支付许可可在同事务设置关闭屏障并释放；有未决尝试只能生成原号QUERY／CLOSE计划，复用事务外渠道合同。禁止order依赖payment具体Mapper或产生模块依赖环。
4. 取消后若旧尝试均有可靠终态且无活动尝试，使用已有可靠事实关闭订单，不要求供应商重复证明。已收款订单不因迟到取消释放库存。
5. 过期扫描／旧订单升级补漏必须覆盖没有支付尝试的订单，按稳定主键分页、短事务、全租户，旧写入退出后从零收尾。

## 持久唤醒竞争

不能只把READY.next_run_at改为现在：任务RUNNING期间发生取消，旧工作者最后写回原到期时间会吞掉唤醒。

任务表V010增量增加wake_generation及claimed_wake_generation（表与新增字段中文注释完整）：

- schedule首次预约业务期望执行时间；重复schedule不改变原时间或终态，需要提前必须显式wake。
- wake在业务事务内递增wake_generation；READY任务提前到DB当前时间，RUNNING只登记新代数，不抢夺有效租约。
- claim记录本次看到的代数；retry时若有更新代数，则next_run_at取当前时间，不能覆盖业务取消。
- 任务fencing仍校验租户／ID／类型／业务引用／owner／token及DB当前有效期，先业务锁后任务锁。
- ORDER_RESOLVE完成只能由同事务已确认订单终态驱动；终态重放不重启任务。通用PAYMENT_RECOVER旧DONE不被回退。
- 数据库时间负责队列租约；订单到期业务时钟统一约定，测试时不能把固定业务时钟误当租约时钟。

代数保留在数据库当前领取快照中，JobLease不新增可被调用方替换的代数字段；fencing_token区分领取，续租不消耗新唤醒。WHERE条件仍检查类型和业务引用。任务READY退避只有在代数未变化时使用原延迟，有新代数立即到期。claim之后到下一次领取期间的新唤醒都不会被退避吞掉。

## 内部API与模块

- core：JobSchedulerPort.schedule(tenant,type,businessKey,runAt)、wake(tenant,type,businessKey)；必须与业务同事务。
- order：创建正金额订单预约；取消及正常收款唤醒；OrderPaymentPort.lockPaymentOrderByNumber；OrderResolutionBackfillService.enqueuePage。
- payment：OrderResolutionPort.prepare返回DONE／WAIT／NETWORK／REVIEW；OrderResolutionService锁订单后检查自身支付意图与尝试，不跨域Mapper。
- jobs：OrderResolutionWorker.runBatch／processLease；本地变化后同事务fencing，渠道调用在事务提交后，资金证据复用密文接收与回调处理。
- 持久化：JobLeaseMapper新增schedule／wake；OrderMapper新增lockByNumber／resolutionCandidates；SQL全部位于XML。没有新增HTTP API。

## 未派发与未决的区分

从未存在支付许可的过期订单，可同事务关闭并释放。已创建尝试但status=PREPARED、submitted_at仍为空时，在订单→意图→尝试锁内确认从未发出CREATE许可，以LOCAL_UNSUBMITTED_V1:原请求号持久记录本地证明，关闭尝试／意图、释放订单并写内部Outbox。它不是伪造渠道回调。

一旦submitted_at已有值，即使网络可能实际没发出，也可能存在暂停后恢复的旧工作者，不能按本地不存在释放；只能走渠道可靠关闭。CREATE许可和本地未派发关闭竞争同一订单行，先取得许可则不走本地释放；先完成关闭则后来的许可不能返回CREATE。

WAIT按业务时钟重新验证订单到期，不以队列租约到期当业务终态；任务调度使用主库UTC。NETWORK仅生成原号CLOSE，渠道返回真实收款则先耐久接收，处理后正常确认支付；普通PENDING／UNKNOWN保留库存。

## 运维与兼容性

DDL先于新应用，V010增加两字段和白名单类型，不回退旧DONE。老Worker不处理ORDER_RESOLVE，新订单任务只由新协议Worker执行；不能把仍在运行的旧支付进程当成支持代数唤醒。回滚应用时须保留兼容新任务的Worker，禁止降级数据库删除资金／任务历史。

V010初始INSERT补漏不代替升级收尾：旧写入及在途事务全部退出后，逐租户从0调用OrderResolutionBackfillService.enqueuePage直到游标不再前进。每页READ_COMMITTED、10秒事务，支持没有支付尝试的订单；跨页不持快照。不能用单个最大ID证明之前所有旧事务都已提交。

REVIEW是人工核对队列，不被普通wake重新启动，也不释放库存；真实收款仍可通过独立回调任务入账。人工恢复／冲突报警／停留年龄指标尚未实现，不能宣称运营闭环完成。runBatch不是常驻调度；租户发现、公平性、停机及生产多实例能力仍待后续。

## 验收矩阵

最终实际结果：build-order-resolution-final.log退出0，123项全通过（Foundation106＋其他17），原始报告归档evidence/order-resolution。共享V010迁移及validate均成功，29表／374字段中文注释完整、订单／任务0行。V001～V010冻结，下一迁移V011；真实常驻调度与全生产验收尚未完成。

- 无支付尝试的过期订单自动取消，库存仅释放一次。
- 订单创建／取消与预约／唤醒任一失败同事务回滚。
- 可靠关闭后旧支付任务DONE，用户再取消仍由订单任务完成取消。
- 用户取消与工作者退避交错，无唤醒丢失；RUNNING期间的wake不允许旧token覆写新任务。
- 过期与收款竞争：真实资金保留，正常付款只确认一次；UNKNOWN不释放库存。
- 旧订单分页补漏幂等、无历史事务快照遗漏；全租户公平调度和优雅停机单独验证。
- 完整回归含现有支付／回调／首单／库存；共享库迁移只在隔离测试通过后执行。

后续：补常驻调度、风险与真实适配器；退款／履约／正式消息／完整裂变范围不变。本地用例不是原三高、容灾或真实渠道认证证据。
