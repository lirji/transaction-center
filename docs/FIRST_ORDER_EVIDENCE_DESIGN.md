# 权威首单补查实施约束

原裂变C03要求按主体及期间补查权威首单，防止事件缺失后无法找回。当前交易原生批量orderIds查询和会员firstPaidOrderNo展示不能替代该能力。

内部先实现以已解析交易memberId为参数的首单补查。为保持现有orderIds请求/数组响应稳定，可使用独立原生路径 `/internal/v1/first-order-referral-evidence:query`，由后续C03 adapter将canonicalSubject请求映射至此路径；原C03合同本身不删除或改写。身份/组织/店铺映射和外部双方签收仍保持未完成。

- 输入memberId、from、to、firstOrderPolicyVersion；仅FIRST_CONFIRMED_PAID_ORDER_V1，期间采用[from,to)，from<to，毫秒精度。只查询当前快照中已经确认的事实，不预言未来，也不预占资格。
- 先读会员永久firstPaidOrderId/firstConfirmedAt，再检查首次本地确认是否在期间内；不能扫描期间订单然后把第二单升级为首单。首单全额退款、迟到早渠道时间或首单在期间外，都不另选第二单。
- 符合期间时，在同一个主库RR事务内读取完整订单证据及正常实收/在途/成功退款、独立completedAt、evidenceVersion。不能调用另开REQUIRES_NEW的现有公开query去拼成混合快照；应复用其私有同事务组装逻辑。
- 缺会员、无首单或首单在区间外均NOT_FOUND且无证据；不可变首单指针/归属/首单标志矛盾须EVIDENCE_UNAVAILABLE，不能伪造结果。READY表示证据可读，不代表商业完成或最终奖励达标。
- 此原生查询不返回canonicalSubject/org/shop的猜测值；正式C03 adapter必须明确映射completedAt与结算/奖励状态，不能把paidAt换个名字当settledAt。

验证：起点包含/终点排除、无首单/外租户、首单在区间外不换二单、全退不换首单、迟到渠道时间不倒换、首单查询后并发退款仍为同一完整快照，以及专用scope/服务身份与输入限制。后续接线须同步机器合同并独立审查。

独立审查补充：响应携带firstConfirmedAt以核验选单依据，不能拿paidAt或渠道成功时间代替。from/to仅筛选首单本地确认时间，金额始终来自当前快照，不是截至to的历史金额。NOT_FOUND可随后续真实首付变化，尤其期间延伸到未来时不能永久否定资格；毫秒归一后再校验from<to。异常指针/归属/首单标志/时序在同快照内失败关闭。
