# 履约与退货授权入口

依据原02-EXTERNAL_CONTRACTS_AND_API第2节五个履约/退货接口及第3节可信服务完成合同。已有OrderFulfillmentService、OrderReturnService和独立库存回库逻辑复用；不重做交付、完成、退款或库存状态机。真实WMS证据核对仍需外部联调，以下入口默认关闭，以已验签JWT、audience、租户及明确主体白名单接收可信人员/服务的事实声明，不能把任意管理员填写的字符串当作验真。

## 权限与业务合同

- POST /api/v1/trade/admin/orders/{orderNo}/fulfillments：trade.admin和trade.fulfillment.write，并通过独立履约主体白名单；deliveryRef、sourceRequestId表示整单全部冻结明细已经实际交付，不能用部分发货调用。201返回原履约单号。
- POST /api/v1/trade/orders/{orderNo}:confirm-receipt：已登录当前会员；必须查本人订单且每个明细均为实物，已有整单真实交付后才能确认。输入不能携带交付证据或自报完成时间。服务或混合订单的商业完成交由可信交付服务。200返回完成状态及原履约单号。
- POST /internal/v1/orders/{orderId}:complete：trade.fulfillment.complete、独立可信服务白名单，JWT只操作其租户；sourceRequestId、deliveryEvidenceRef。读取原交付事实后独立商业完成，不以paidAt代替交付。200返回原履约单号。
- POST /api/v1/trade/admin/returns：trade.admin+trade.return.write及仓储主体白名单；orderItemId、warehouseId、quantity，201返回RMA申请号。请求只有退货意图，不增加库存，不把申请当收到实物。
- POST /api/v1/trade/admin/returns/{returnNo}:inspect：相同仓储权限；result=ACCEPTED/PARTIAL/REJECTED、restockQuantity、evidenceRef，使用申请数量作为本次实际收到量。ACCEPTED可售等于收到，PARTIAL介于0和收到之间，REJECTED可售0。200返回申请号与独立验收事实号；调用已验证OrderReturnService.inspect，同事务写申请终态与库存验收事实。退货数量上限仍由原订单明细永久已收预算兜底，不因为存在RMA申请就承诺可以突破剩余预算。

所有写接口需要Idempotency-Key；外部sourceRequestId与HTTP键同时绑定规范请求，任一复用变更内容均拒绝。管理验收更新使用强If-Match；旧版本412，缺少428。管理查询增加GET /api/v1/trade/admin/returns/{returnNo}，返回请求数量、状态、version及验收号，不返回原始证据内容。原有订单GET继续展示履约/财务状态。

## 持久化与服务器时间

计划新增V029：
1. tc_fulfillment_ingress：租户+真实主体摘要+操作+来源事件永久唯一；固定操作枚举，规范请求hash、首次服务端观察时间、首次结果资源号/状态/版本。HTTP键由既有Commands按原保留窗口管理，来源事件永久绑定，不依赖Commands作为来源事实凭证。来源回执先UPSERT再当前读。首次服务端时间传给已验证交付/完成服务，重放读原时间和结果，避免Commands淘汰后使用新now造成伪冲突。
2. tc_return_request：RMA编号、租户、原订单/明细/仓库、申请数量、OPEN/INSPECTED、独立验收事实号、版本、时间。申请不扣库存、不代替验收累计预算。表与每字段中文注释，资金表和旧迁移不改。

入口先Commands占HTTP键，再永久来源回执，再锁当前申请（验收时），再调用订单域的原事务编排；回执、RMA、领域事实与审计共同提交。源事件永久唯一仍由原核心事实表验证；Commands保留期内同HTTP键改sourceRequestId拒绝，不同HTTP键同源事件按永久来源回执读取首次结果，不重新取now。来源hash不包含HTTP键或服务端时间，只绑定规范业务内容。会员确认用原订单号作为每主体固定来源事件；RMA创建以Idempotency-Key作为永久来源事件；验收以returnNo作为固定来源事件，当前请求含If-Match旧版本仍可重放原结果，改变版本或验收内容则冲突。这样无需额外永久HTTP别名表，也不把配置好的命令保留期静默升级为所有接口永久HTTP键承诺。

会员确认无来源事件，由永久入口键记录首次结果；已完成的同主体再次确认应返回原完成状态，不覆盖原完成来源/时间。不同主体无权读取本人订单；冻结会员允许阅读/确认既有真实履约，不增加新交易受理权限。

## 验证和边界

重点验证未交付/服务商品/跨租户/非本人拒绝；白名单与scope分别不足拒绝；同源不同HTTP键及Commands淘汰永久重放；来源内容冲突；两次会员确认与可信完成竞争；RMA创建不变库存、验收唯一回库、并发总预算、版本冲突及审计失败全部回滚。新增准入开关不影响已受理订单履约和退货。

真实WMS来源、仓库角色与生产身份授权必须外部验证，默认关闭不能声称已可生产上线。全文原161项、原生产验收及完整裂变不缩减。

## 独立设计审查落实

会员/可信服务完成竞争必须在订单锁内判断已存在的商业完成事实并返回原结果；不能把新的来源和时间直接交给现有complete方法覆盖或触发伪冲突。为保持Commands→来源回执→RMA（若适用）→订单锁序，包装层完成调用需复用抽取后的订单域同事务完成逻辑，不能在已锁订单后反向争用另一笔Commands键。已有核心complete的严格来源冲突语义保持原样，新入口只能在验证授权和订单归属后复用已完成结果。

PARTIAL只表示申请全量已经实际收到，其中部分可售；不表示部分到货。部分到货不能调用本验收合同，需要先按实际批次数量创建独立申请；所有批次仍受原明细累计实收预算约束。入口结果资源不加外键，避免INSERT回执隐式先锁父行；RMA创建读取不可变原明细校验，不反向锁既有RMA。两张表足够，不引入通用工作流或额外HTTP别名表。
