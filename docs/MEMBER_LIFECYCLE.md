# 会员生命周期与查询能力增量

本阶段只修改交易中心，不改造现有平台。SQL使用新V003，已执行的V001/V002保持不变；新增tc_consent_document、tc_member_consent、tc_operation_audit三张表，所有表／字段均有中文注释。

## 业务口径与并发

1. 资料修改仅允许展示名称，不接收tenantId、subject、手机号或支付历史；目前不收集需另建加密／验证流程的资料。
2. 会员状态限定ACTIVE/FROZEN。冻结／解冻需要后台scope及原因，必须匹配expectedVersion；相同状态不生成假转换。冻结不修改everPaid／首单事实，不删除历史。
3. 资料、状态、同意操作共用会员行锁／version，命令结果、业务变更、审计、Outbox在同一事务中提交；同版本并发修改只允许一个成功。
4. 条款由受权后台发布，租户＋purpose＋documentVersion唯一且不可覆盖；URL和内容摘要是审计引用，当前服务不抓取远程正文，也不宣称已验证其法律效力或实时内容一致性。发布端应使用不可变内容地址并审核摘要。
5. 已发布版本均可被显式选择；当前不实现“某用途必须同意最新版本”的合规策略。业务需要强制最新版本时应增加独立启用／强制策略，不能把版本字符串排序当最新版本。
6. 注册不会自动勾选或记录营销同意。GRANT必须引用已发布版本；冻结会员拒绝新增GRANT，仍允许WITHDRAW。
7. 同一用途的新GRANT替代该用途之前有效版本；WITHDRAW必须匹配当前最后GRANT的documentVersion。撤回后重放旧幂等GRANT只返回原事件ID，不重新授予同意。
8. 同意记录只追加，memberVersion提供顺序；事务中的最近动作读取使用当前锁定读，避免外层事务旧快照造成撤回错误版本。

## 新增API（均已实现）

| 方法 | 路径（前缀/api/v1/trade） | 要点 |
|---|---|---|
| PATCH | /members/me | expectedVersion、displayName |
| GET | /admin/members | 后台会员列表，不含认证主体信息 |
| POST | /admin/members/{id}:change-status | expectedVersion、targetStatus、reason |
| POST | /admin/consent-documents | 发布purpose／documentVersion及内容引用 |
| GET | /consent-documents | 本租户已发布条款列表 |
| POST | /members/me/consents | expectedVersion、purpose、documentVersion、action |
| GET | /members/me/consents | 本人追加型历史，不暗示所有历史仍有效 |
| GET | /products/{id} | 只展示已发布商品 |
| GET | /admin/products/{id} | 本租户草稿或发布商品详情 |
| GET | /admin/warehouses | 仓库有界列表 |
| GET | /admin/inventory-ledgers | warehouseId／skuId必填，有界游标 |

全部写命令沿用Idempotency-Key和200 resourceId约定；机器合同见[openapi.json](openapi.json)。所有新增读取只查询数据库，不生成前端Mock或修改业务状态。

## 未扩展的边界

- 尚未开放会员注销／匿名化、手机号更换、多身份合并或任意消费事实修改。
- 审计当前持久化供受控核查，未开放通用审计导出API。
- 不提供订单、报价、现金支付或退款假接口；冻结对未来购买和退款的影响需在相应订单／资金流程中接入，不能仅凭会员状态接口宣称整条交易链路已限制。
- 库存和会员列表不是生产容量证明，仍须完成仓储边界、压测及索引计划验证。
