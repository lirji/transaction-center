# 库存V1与可靠发布阶段

库存事件trade.inventory-changed.v1在每笔实际库存流水后生成，余额通过InventoryMapper专用FOR UPDATE按tenant/warehouse/sku当前读，所有入口此时已经持有对应余额行锁，不在RR旧快照中拼新版本。data=warehouseId/skuId/operationNo/available/reserved/sold/stockTotal/balanceVersion；aggregateType=INVENTORY_BALANCE、aggregateId=warehouseId:skuId、aggregateVersion=balanceVersion。数量是非负int64并验证守恒，货币安全整数上限不套用为新的库存业务限额。

普通ADJUST/RESERVE/CONFIRM/RELEASE由统一ledger接线。回库沿用原排序锁仓规则：同仓一笔IN，跨仓在两个余额都更新后分别产生OUT/IN快照事件；各自版本和operationNo，不把调拨加成两份新增总库存。不可售验收不发库存事件。重复命令/重复终态/永久验收重放不追加事件。事件写失败与余额、预占、流水、验收预算整体回滚。加入注入Clock，时间为本地记录时间，不代替版本顺序。

发布后续复用JobSchedulerPort/JobLeaseService，新增白名单OUTBOX_PUBLISH任务（须新V022迁移，不修改冻结V001～V021）。V1写入与任务同事务，历史V1以有界当前读补漏，v0不补发。发布器只接受显式支持类型且索引列/完整payload一致；未知或损坏事件隔离。准备/租约检查短事务→网络发送→ACK后本地SENT与任务完成同事务fencing；发送成功而本地提交丢失可重复，永久eventId和字节不变。网络超时不能记SENT，失租不能由旧Worker提交成功。

Kafka默认关闭、配置固定bootstrap/topic映射，键为tenant+aggregateType+aggregateId；消息不按发布时业务状态重建。acks及超时/幂等生产者参数需通过实际客户端验证。复用dev_infra kafka38，生产复制/ACL/配额/来源认证/重放窗口仍独立待验，不能用单机测试冒充。消费者仍以eventId去重与业务证据版本处理乱序，Kafka幂等不替代跨进程业务去重。

阶段测试：库存各转换/跨仓双事件/回滚/永久重放；发布ACK丢失、失租、重试/隔离、v0拒绝及真实Kafka收取完全相同载荷。先集中定向，完成阶段一次全量。
