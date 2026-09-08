# 订单归因令牌与冻结快照

来源：原02-EXTERNAL_CONTRACTS_AND_API订单创建及Marketing→Trade边界、01-DATABASE_DESIGN.referral_ref、TC-05。交易API现已接入可选归因令牌（默认关闭），营销仓库尚未找到可复用的归因令牌签发器；measurement的分析归因不能冒充奖励授权。以下为实现合同，营销签发方后续在原平台接入，不另建身份或营销系统。

## 令牌合同

订单创建可选referralToken。使用独立营销签发方配置和固定JWKS，专用audience=transaction-center-order-attribution，RS256，禁止用请求中的密钥地址。令牌必需iss/aud/iat/exp/jti及token_kind=ORDER_REFERRAL_ATTRIBUTION_V1、schema_version=1、tenant_id、actor_key、member_id、quote_id、referral_ref、campaign_id、definition_version。tenant/member/quote/actor同时绑定实际认证主体和冻结报价；令牌不能改金额、SKU或受益人。actor_key沿用现有Actor.key的issuer+subject规范摘要，不创造第二套canonicalSubject。C02/C03最终权威主体映射仍单独保留缺口。

iat不得晚于本地now，exp必须大于iat，nbf存在时不得晚于now；有效期最多15分钟，jti/referral_ref/campaign_id最长64，version为正整数；所有ID按字符串处理。单次消费以tenant+issuer+jti唯一约束持久绑定唯一订单，失败事务不占用；同token不同订单不能创建。保存不可变归因引用/活动版本/令牌摘要，不保存原令牌，不将引用解释为已满足裂变资格。取消或退款不释放令牌身份供第二单使用。

## 事务与幂等

JWKS加载/签名验证在交易事务外，并用NEVER传播或事务guard实际拒绝外层事务调用；原无token内部组合事务路径保持兼容。令牌总长不超过16KiB，取钥连接/读取各有明确有限超时，不从token头取远程地址。新建前在本地事务重检令牌有效期、tenant/actor/member/quote绑定，在商户锁等待/地址加密后、订单实际插入前再校验有效期，订单+归因快照+库存+Outbox同事务提交。先取得既有Admission策略，暂停期间不产生新订单。

原命令已完成且请求摘要完全相同时，应直接返回原订单，不因令牌过期或旧密钥退休改变历史成功。为此Commands增加作用域严格绑定actor/operation/key/hash的只读完成结果查找，缺失时再验签，最后仍由原Commands.run串行化并检测同键不同内容。查询不能代替最终事务去重，不能通过其他actor读取原结果。归因快照同时永久保存原actor_key、operation、幂等键及完整请求摘要，并加对应唯一键；Commands缓存被清理后仍能按这些字段匹配返回原订单。不可仅凭jti返回，否则会错误接受换键或修改地址。请求摘要包含完整令牌摘要；无令牌旧请求保持原摘要兼容。

## 存储与外部状态

V032新增订单归因冻结快照及单次消费唯一键，含中文表/字段注释、永久原请求匹配字段、tenant/order外键、长度/正版本约束。不改V001～V031。默认功能关闭；无token维持原行为，传token而未接通时明确拒绝，不静默丢弃。新错误码必须进入SDK/机器清单。OpenAPI增加可选字段但不暴露原token回显。

## 验证计划

真实RSA签名、错误issuer/audience/租户/主体/会员/报价/版本/过期、签名篡改、唯一消费竞争、事务回滚、不复用取消单token、原幂等在过期后重放及其他actor隔离、原金额/库存不变、network不在TX。独立审查通过后迁移共享库。营销真实签发与订单资格事件消费尚待跨平台实现，不能把本地验证器当成完整裂变。

独立设计复核已完成：原方案符合TC-05；补充永久原请求回放、iat/nbf/锁后有效期重检、实际禁止外层事务验签及取钥超时后进入实施。

## 实施状态（2026-09-08）

已接入 OrderInput.referralToken、OrderAttributionVerifierPort、固定JWKS的真实RS256验证器和永久快照Mapper。OrderService保持无token旧摘要，带token按完整令牌摘要扩展；两次永久请求查询防Commands清理及竞争，旧无token外层RR事务用独立短读读取最新永久事实。已有订单读取仍进行本人会员隔离。

默认 trade.order-attribution.enabled=false。启用前必须先迁移V032，再配置 TRADE_ORDER_ATTRIBUTION_ISSUER / TRADE_ORDER_ATTRIBUTION_JWKS（固定HTTPS），并完成原营销签发方接入；access audience不得复用专用归因audience。功能回退关闭开关后仍允许匹配永久凭证的原成功请求回放，不删除快照或回退已执行迁移。

新增错误：ORDER_ATTRIBUTION_DISABLED=503，ORDER_ATTRIBUTION_UNTRUSTED=403，ORDER_ATTRIBUTION_TRANSACTION/CONSUMED=409；已同步ErrorCodes、机器清单及OpenAPI。传递原令牌不在响应或日志回显，数据库只留摘要及签名事实。

本阶段组合全量428项/34套通过（含SDK/自动准入），随后3项断言收紧专项通过；独立审查无待修阻断。共享V032执行/validate通过，48表678字段中文注释完整。原始记录见 `.codex/tasks/referral-end-to-end/evidence/order-attribution/`。营销真实签发方、权威映射和生产验收仍未接通。
