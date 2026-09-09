# 独立退货验收与唯一回库

> 时效说明（2026-09-08）：本文保留 V021 唯一回库核心与历史验证。后续 V029 已增加 RMA 申请、管理查询/验收及来源授权 HTTP，见 [授权入口](FULFILLMENT_ACCESS_DESIGN.md)。正文“公开流程尚未接线”属于早期范围，真实 WMS 外部验真仍需联调。

本切片提供可信内部仓储验收服务 `OrderReturnService.inspect`；实际整单交付来自 V020 履约事实。退款是否申请、批准、拒绝、UNKNOWN 或成功，都不自动创建验收或变更库存。真实 WMS 身份验真、公开管理售后申请/待验收流程及运营查询仍未接线，不能将内部服务视为完整生产售后入口。

输入包含永久来源事件号、原订单/明细、接收仓库、实收数量、可售数量和验收证据引用；Actor 必须由可信上游构造。当前入口记录已经完成检验的结果，不产生或模拟待验收状态。全部不可售仍记录实收事实并消耗累计实收预算；同事件不能改判为可售，残次品修复再销售不属于当前合同。

- `tc_order_item.returned_quantity`：累计实收（含不可售）不超过冻结的实际整单交付数量。服务商品拒绝实物退货。
- `tc_inventory_reservation.restocked_quantity`：累计可售回库不超过同一原 CONFIRMED 预占，SKU 与原始数量必须吻合；预占历史终态保持 CONFIRMED。
- `tc_return_receipt`：租户＋来源身份＋事件永久唯一，命令淘汰后仍核对完整摘要。
- `tc_inventory_return_operation`：永久父操作绑定原预占与接收仓；原预占先加锁，再插入永久键并当前读。库存专用 Port 必须加入验收事务。

同仓可售回库 r：available+r、sold-r，reserved 和 stock_total 不变。跨仓：原仓 sold-r、stock_total-r；接收仓 available+r、stock_total+r，两仓 reserved 不变。双仓余额初始化 UPSERT 也按 warehouse/SKU 排序，不能先碰目标仓再排序。父操作派生固定 `:OUT` / `:IN` 流水键；同仓仅一条 `:IN`，跨仓两条，均与事实、数量预算、Audit、Outbox 同事务提交。sold 表示尚未回到可售库存的净售出记账量，不能解释为仍在客户手上的实物数量。

不调用盘点 adjust；不将新回库数量重复加到全局总库存；不借用其他订单的 sold 补足原预占预算。零可售不创建库存操作、余额或流水，但接收仓仍必须属于同租户。退款与订单原 paidAt、completedAt、everPaid 互不覆盖。

V021 是新增迁移，V001～V020 已在共享库执行冻结。Mapper XML 是唯一生产 SQL 入口。测试以隔离 MySQL 8.4 验证重复/并发、累计预算、双向跨仓及首次目标余额、外层回滚、独立库存 Port 预算和退款三类结果。最终测试及共享迁移状态以任务档案 evidence/returns 为准，进行中的测试不能标记通过。

最终验证：`mvn -q -l build-return-final.log verify`退出0，268项、0失败/错误/跳过。共享V021及21版validate成功，39表538字段注释缺失0，验收及回库操作各0行。26个Mapper/189条SQL，HTTP保持45操作/35路径；未部署或执行真实业务退款。
