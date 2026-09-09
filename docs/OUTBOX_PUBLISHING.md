# Outbox可靠发布

> 时效说明（2026-09-08）：本文保留 V022 发布切片记录。后续已实现 [后台队列与永久受控重放](OUTBOX_OPERATIONS.md) 以及 [周期历史补漏](OUTBOX_MAINTENANCE_OPERATIONS.md)，正文“尚无重放/自动补漏”属于早期边界。生产 Kafka 配置和消费者验收仍未由本页证明。

V1事件写入与OUTBOX_PUBLISH任务同一业务事务提交；发布关闭时仍入队。旧v0只保留内部，不补发。固定目录包含注册、正常支付、完成、取消、订单退款证据变化、退款成功、库存变化七种类型；索引tenant/eventId/eventType/aggregateId必须与严格解码后的载荷一致。发送数据库保存的原payload，不在发布时重建业务状态。

工作者固定Outbox→job锁序：当前锁读与完整合同验证后同事务续租30秒；提交后在事务外发送；Broker确认后短事务记录SENT、主题、分区、offset和数据库UTC时间，并在末尾完成带fencing的任务。旧租约失败将整个本地成功变更回滚。准备时已SENT只完成任务，不再发送。ACK后本地数据库故障保留租约等待接管，可能重复发送相同事件；消费者必须以eventId永久去重，并以聚合版本判断乱序。

不确定发送使用原事件退避，失败只存脱敏分类与首尾时间；第10次失败或超过10次领取转REVIEW。无效合同、非规范UUID任务键和v0任务直接隔离；无效任务不能因锁读参数校验失败无限重领。失败登记不覆盖SENT，也不改写历史载荷。REVIEW是持久人工处理状态，目前尚无对外人工重放入口或独立DLQ主题，不把它描述为完整运营闭环。

发布默认关闭。API的Compose配置固定关闭，worker另设TRADE_OUTBOX_ENABLED，默认false；打开时必须提供TRADE_OUTBOX_BOOTSTRAP_SERVERS，dev_infra容器地址infra-kafka38:9092、宿主127.0.0.1:49092。TRADE_OUTBOX_TOPIC_PREFIX仅部署时配置，固定追加完整eventType；不得由业务输入指定Topic。OUTBOX通道关闭时线程数为0，不发现租户/领取。启用后的容量通过TRADE_WORKER_OUTBOX_THREADS控制，参与总16槽位上限。

Kafka客户端复用Spring Boot 4.0.2管理的4.1.1版本；acks=all、enable.idempotence=true、max.in.flight=5、max.block=5秒、delivery.timeout=10秒、request.timeout=5秒、linger=0、ACK等待15秒。固定String序列化/无自定义拦截器，正常客户端阻塞等待预算最多20秒，为租约保留提交余量；这不是进程暂停、DNS或操作系统失效条件下的端到端硬实时保证。默认客户端仅用于当前dev_infra联调，生产认证/ACL、复制与ISR、配额、网络隔离、告警和容灾尚待配置与验收。

V022只追加任务类型和8个Outbox发布元数据字段，全部中文注释，不改动V001～V021。历史补漏须在旧写入者及其在途事务退出后，对每个租户从0调用OutboxPublishBackfillService.enqueuePage至游标不变；每页1～100个Outbox，RC短事务扫描所有类型/状态推进游标，只给PENDING白名单V1补任务；唯一键不会复活DONE/REVIEW。当前无自动历史全租户补漏调度，运营须记录租户扫描完成凭证。

验证分开保留：Foundation真实MySQL覆盖入队原子回滚、关闭不领取、ACK位置、SENT重放、失租回滚及同payload重发、不确定退避/隔离、异常长键、历史补漏；KafkaEventPublisherTest检查实际客户端合同，不能替代Broker证据。KafkaPublisherIT显式使用dev_infra专用主题transaction-center-verification.trade.member-registered.v1，按本次ACK的分区offset精确收取原JSON/分区键/eventId头，不删除共享主题或消费组。运行命令：`mvn -pl transaction-server -am -Dtest=KafkaPublisherIT -Dsurefire.failIfNoSpecifiedTests=false -Dtrade.test.kafka.bootstrap=127.0.0.1:49092 test`。该IT不属于默认Surefire测试名匹配，结果须单独存证。
