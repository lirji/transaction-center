# 原生订单裂变证据一致查询

`OrderReferralEvidenceService.query(tenant, orderIds)` 为可信内部查询，提供正式交易字段的快照基础。每次最多100个ID；保留输入顺序及重复项，底层去重集合各域只查询一次，共三条SQL。每项独立 FOUND/NOT_FOUND，不泄露其他租户订单的存在性。暂未开放HTTP；原生会员策略已实现；可信服务入口、canonicalSubject/组织店铺适配和按主体补查首单仍待完成，不能当作完整C02/C03接通。

订单、正常支付、会员首单通过各域专用Port读取，各域Mapper只查自己的表。编排层使用同数据源独立只读REPEATABLE_READ事务；第一条普通SELECT建立快照，后续Port MANDATORY加入，不混行锁、新事务或副本。独立快照有意不见外层未提交写入，因此不能用本查询构造正在写入的V1 Outbox payload，事件必须从原写事务自身事实组装。

返回字段包含：orderId/memberId、evidenceVersion、FIRST_CONFIRMED_PAID_ORDER_V1及是否首单、paidAmount/refundedAmount/pendingRefundAmount/netPaidAmount/conservativeEligibleAmount、currency/paidAt/独立completedAt/asOf。

- paidAmount仅来自NORMAL收款，并核对订单应付、会员原支付事实的金额、币种和归属。多付、错额、关单后实收及相应补偿全部排除。
- netPaidAmount=正常实收−成功退款；conservativeEligibleAmount再扣在途预占。拒绝/过期/可靠失败释放与成功不混为一谈。金额或归属缺失矛盾返回EVIDENCE_UNAVAILABLE，不能默认具备奖励资格。
- 首单取本地首次确认事实，退款到零也不清除。零元完成订单有completedAt，但paidAt为空、消费额零、不是消费首单。
- 订单evidenceVersion与正常退款预占/释放/成功等变化共同推进；异常补偿不推进正常金额或版本。
- asOf是本次读取完成时间，用于说明此次读取；不是MySQL MVCC精确提交时间或历史金额快照。所有金额、首单和版本仍属于同一个数据库快照。

新增集合Mapper的foreach只使用绑定值；合同门禁验证collection对应声明参数、局部元素必须实际绑定，继续禁止文本替换及未声明参数。数据库V021不变，本切片不新增表。

验证目标：正常支付/部分及全额退款/在途与释放版本、异常补偿排除、零元服务、100条批量/三查询/租户隔离、订单读取后并发退款提交仍返回旧快照、外层RC不降低隔离、外层未提交写入不进入查询、不完整事实失败关闭。最终结果以任务evidence/order-evidence为准。

最终验证：`mvn -q -l build-evidence-final.log verify`退出0，279项全部通过（0失败/错误/跳过），独立审查无待修项。共享V021不变；HTTP与外部映射尚未接线。原始报告见evidence/order-evidence。
