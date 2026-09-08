## 订单归因阶段完成（2026-09-08）

归因令牌、永久快照、单次消费及Commands淘汰后原成功回放已接入，默认关闭。真实RS256/JWKS、外层事务拒绝、商户锁/地址加密后时间复检、租户/主体隔离、无token兼容、原子回滚及取消不释放均有证据。

验证：组合 `mvn -q -l build-attribution-full.log clean verify` 退出0，428项/34套，失败/错误/跳过均0，包含归因、SDK及自动准入。随后仅收紧3项测试断言，定向补验3项退出0；不把它们相加成431项全量。独立审查关闭同报价掩盖令牌竞争的测试缺口，实际使用两份报价竞争同issuer/jti。证据位于 `.codex/tasks/referral-end-to-end/evidence/order-attribution/`。

共享dev_infra V032迁移及32版validate通过，48张业务表678字段，表/字段中文注释缺失0，归因表0业务行。V001～V032冻结，下一未占用号V033。无应用部署、真实业务开关启用、提交或推送。GitHub Actions新增构建/隔离测试及报告保存，YAML/bash语法和底层本地命令验证；远程CI/安全扫描未执行。

后续并行mapping_adapter交易内部失败关闭映射适配、referral_runtime原营销平台纯规则SPI，属于新切片，**不在上述428全量覆盖内**。真实映射来源已向用户询问，隐私提案、真实渠道和生产参数保持待确认；保留161项及完整裂变/生产门禁。

# Code Review Report

## 审批HTTP最终验证（2026-09-07）

固定verify→accept、专用trade.refund.approval作用域、默认关闭及固定HTTPS/JWKS/专属audience/服务主体已接通。exec52443（mvn -q -l build-approval-http-final.log verify）退出0，240项全通过，0失败/错误/跳过；14份原报告在evidence/approval-http。独立只读审查无确定越权漏洞，报文边界/缺密钥503/关闭MVC503/拒绝无副作用均补验。Compose静态config --quiet退出0，未部署；Worker强制关闭审批入口。

45HTTP操作/35路径，23Mapper/175SQL，共享V017不变，本轮无数据库迁移或共享业务写入。HTTP集成Mock Verifier，密码学由真实RSA独立验证；默认关闭是配置上下文＋独立MVC测试，不能把这些说成真实供应商HTTP密码学全链路。后续统一真实RSA/HTTP/DB联测、拒绝安全审计/告警、真实流程配置/签署与密钥预算仍保留；异常退款/退货/正式资格/三高和完整裂变未完成。下文运行中及旧状态是历史记录。

## V017审批原子受理最终验证（2026-09-07）

同步密文证据与审批/资金组件拒绝释放/审计/Outbox/持久唤醒原子受理已实现，无HTTP写入口。最新exec23123（mvn -q -l build-approval-receipt-final.log verify）退出0，236项全部通过，0失败/错误/跳过，含7个approvalReceipt用例和真实RSA回执校验。独立只读审查无确定安全缺陷，建议的跨租户事件、末尾过期回滚及合法拒绝重复均已补验。

共享dev_infra已执行V017，exec39048退出0，17版validate成功；36张业务表/497字段中文注释缺失0，审批证据0行。原始13份测试报告、迁移巡检和审查位于evidence/approval-receipt。23Mapper/175SQL、44HTTP操作/34路径。V001～V017冻结，后续V018。无部署/共享业务测试数据/其他平台修改/提交推送。

后续仍须固定verify→accept的专用scope默认关闭HTTP、生产流程身份与密钥配置、错误安全审计；异常退款/退货/正式资格事件及原生产门禁和完整裂变均未完成。以下旧状态仅为历史记录。

## 原渠道绑定最终接线验证（2026-09-07）

PaymentChannelBindingPort/Service、原尝试Mapper XML、RefundChannelService强制绑定及Adapter三参数合同已实现，缺引用Worker重试保留资金。exec16378全量verify退出0，223项全部通过；此后新增凭据缺失Worker测试，首次exec36871测试变量类型编译错误已修复，exec11413最终专项22项全部通过（Foundation新增1＋Binding4＋RefundChannel13＋Mapper1＋Quality3）。不能声称单次224项全量通过。实际生产代码的全量验证已通过，最后测试变更另有专项证据。

独立只读channel_binding_review未发现确定安全缺陷，建议的缺引用Worker案例已补并通过。原始报告、失败日志及审查位于.codex/tasks/referral-end-to-end/evidence/channel-binding/。真实MySQL验证原路解析、跨租户拒绝、冻结历史配置与事务外网络，合同测试验证错渠道/商户/币种/租户/尝试/凭据拒绝，以及Binding原样传递。22Mapper/172SQL；无DDL和HTTP变化，共享V016；无应用部署、共享业务写入或其他平台改造。

真实供应商/托管密钥权限与轮换/强制超时仍未完成。下一步继续可信审批与退款后台、异常多付补偿、独立退货、正式资格事件、生产门禁和完整裂变；不要重做已经通过的原路接线。以下较旧状态为历史记录。

## V016最新验证（2026-09-07）

退款主动恢复/认证同号重放/审批到期/持久唤醒/滚动补漏及第五运行通道已实现。全量exec35319（mvn -q -l build-refund-recovery-final.log verify）退出0，217项全部通过：Foundation175＋RefundChannel11＋其他31，0失败/错误/跳过。共享dev_infra迁移exec86670退出0，V016执行且16版validate成功。35张tc_业务表/485个业务字段中文注释缺失0，退款恢复任务0条；历史36/495包含Flyway历史表，不能混同业务注释口径。原始报告和迁移巡检在.codex/tasks/referral-end-to-end/evidence/refund-recovery/。

22Mapper/171SQL、43HTTP操作/33路径。独立审查发现旧实例审批后未来任务不被补漏提前，已改READY/SUBMITTED使用wake并新增回归，最终全量包含该测试。无真实渠道适配器、无应用部署/共享测试业务写入/其他平台修改/提交推送。默认Worker仍关闭；V001～V016冻结，后续新增迁移V017。

这是内部切片证据，不是完整生产验收。下一步为原路凭据公开Port与调用绑定，继续可信审批/异常补偿/退货/正式事件资格、真实渠道/隐私/运营/CI/三高与完整裂变。下文旧版本和运行中描述均为历史记录。

## V015退款Inbox（当前全量验证中）

V015退款持久Inbox、核心证据保护Port/装配层复用、租约回调Worker及第四独立通道已实现。13项Foundation退款Inbox测试；初轮4425运行时测试缺新Worker替身及Mockito事务代理设桩错误，二轮13220 MySQL异常包装断言和嵌套Mockito时钟桩错误，均修复并保留失败报告。当前exec22616/build-refund-inbox-final.log全量运行，尚无最终结论；共享仍V014。新增22Mapper/170SQL，HTTP不变43操作/33路径。后续REFUND_RECOVER、真实供应商及完整裂变仍缺。

独立Agent refund_inbox_review只读复核事务/锁序/事件唯一性/AAD/可靠失败及DDL NULL约束，未发现阻断缺陷；未代跑测试。新增真实MySQL用例覆盖陈旧REVIEW回滚、伪造业务引用、字段重算摘要仍不能绕过认证。十秒网络预算/真实验签/KMS/生产容量仍非已完成能力。

## 退款渠道边界审查（2026-09-07）

独立Agent refund_channel_review实际只读审查RefundChannelPort/Adapter/Service、RefundEvidencePort及VerifiedRefundEvidence，未发现可复现阻断缺陷；确认事务外/缺接收器禁发网/原路绑定/UNKNOWN无资金结论/异常证据保留/接收失败传播/原文复制及脱敏。没有把此审查称为测试或真实供应商认证。

自审发现商户号限制128与已执行V007的VARCHAR(255)不兼容，修复为255并新增边界测试。全量exec58350在修复前已编译，须完成后专项复验最终源码。十秒仅传入预算，真实强制超时、验真、Inbox持久化与Worker/fencing仍未实现。

## V014退款应用独立只读复审（2026-09-07）

callback_inbox_review实际审查新模块/OrderRefundPort/正常实收定位/Mapper/V014，主Agent另外复核。确认P2：旧CHECK只约束PENDING_APPROVAL，内部错误更新为READY后prepare可按状态放行但缺批准证据。已补数据库审批状态关联CHECK及prepare防御，并新增SQL负例验证；复审确认修复。EXPIRED只允许待审批且到期，资金HELD释放/组件/应用/订单/Outbox同事务，保留空审批决定而非伪造REJECT。

自审另修复配置幂等响应返回后来最新策略的问题：现在PATCH返回该命令原应用的策略快照，GET仍读取当前；测试保留两次变更后原命令重放。正常实收定位复核来源订单/金额/币种/NORMAL，不只相信意图指针。HTTP显式校验组件数量/金额，DTO默认日志脱敏。

首次19项前的专项测试夹具问题分别为重复SKU编号、审计表断言误写tc_audit_log，均按实际模式修复，未放宽业务断言；原始报告在evidence/refund-application。最终结果以QA/VERIFICATION为准。未发现确定性超退路径，但未对真实渠道/流程验真、退款耐久接收和自动Worker作通过声明，仍为生产未完成项。

## V013退款资金前置复审（2026-09-07）

独立只读审查由callback_inbox_review实际执行，主Agent另行核对代码/SQL/测试；工作树未跟踪文件全部保留，不以空git diff认定无变更。范围为PaymentRefundPort/Service、两个model、Mapper/XML、V013和Foundation退款测试，不覆盖尚未实现的退款应用、渠道或审批。

确认并修复：成功重放只检查渠道号及金额币种，未检查渠道成功时间；现对输入时间按毫秒归一，再与原事实比较，新增时间冲突测试。V013还同步更新账本表/方向字段的旧“仅收款”注释，未修改已执行V007。

复审未发现确定性超退、重复资金记账或DDL约束缺陷：收款条件更新加CHECK限制额度；退款永久唯一号与来源绑定；派发后的未知保留预占；资金/账本/Outbox与外层事务及末尾fencing组合。复审建议的终态竞争、混合退款余额、跨订单渠道号冲突已补测试，最终运行结果见QA及验证记录。审查Agent未运行测试或写共享数据库。

剩余风险：此Port依赖可信调用方提供审批及不可再成功证明，没有实际验签能力；释放后的矛盾成功需由未来耐久Inbox保留并人工核对，不能丢弃原始证据；当前无退款HTTP、自动补偿、应用明细上限或生产容量证据。整体继续in-progress。

## V012支付风险门禁独立复核（2026-09-07）

按long-task-continuity的高风险要求，由callback_inbox_review独立只读审查真实代码，没有运行测试或修改文件。确认并修复：

| 严重性 | 场景与证据 | 修复与验证 |
|---|---|---|
| P1 | 高attempts退避最小80秒，风险ALLOW最多60秒，每次调度都过期导致永久重评 | PaymentRecoveryWorker.java:117决定后100ms重检，WAIT才指数退避；真实8次领取/退避后批准并最终CREATE测试通过 |
| P2 | SQL CHECK在ALLOW截止时间为空时为UNKNOWN，不会拒绝空期限记录 | V012__payment_risk_evaluations.sql:31显式IS NOT NULL；数据库3819负例通过 |

另补风险：原空结果FOR UPDATE范围查询有间隙锁隐患（未复现实例，不计已确认缺陷）；改为当前评估指针及同租户同尝试复合FK，12并发首次评估和同尝试幂等通过。PaymentDispatchService.java:57及Worker调用点在fencing等待后重检CREATE；实际持任务行锁、确认工作者未完成、推进业务Clock再释放锁，验证已过期许可的submitted标记及新增评估一起回滚。没有声称观测到全部MySQL锁等待队列或实等60秒。

最后限定复核未发现新增确定性高风险问题。主代理最终build-payment-risk-final.log全量150项通过，原始证据evidence/payment-risk。内部审查conditional-pass：真实供应商签名/时限/配额/风险字段/身份认证、REVIEW人工恢复、时钟偏移和生产容量仍未验证；不能以可信内部Port等同于已完成外部验签。普通HTTP调用者没有提交风险决定的接口。共享V012已增量执行，未部署或真实收款。

## Worker运行时独立复核（2026-09-07，最终136项全通过）

依照long-task-continuity高风险要求，callback_inbox_review独立只读审查实际实现，确认两次P1吞吐问题：每个未来租户都睡250毫秒；改为绕回后，又把首租户为空误判为整轮空闲，持续热租户单槽位被限制约4任务/秒。已改空探测快速轮转及共享整轮处理计数基线，在`WorkerRuntime.loop/Lane.next`落实；`manyFutureTenantsDoNotAddOneIdleDelayPerTenant`和`futureFirstTenantDoesNotThrottleContinuouslyBusySecondTenant`分别覆盖原场景。

最后限定复核认为两项修复成立，未发现新增高风险问题；审查没有执行测试。主代理在修复后完成build-worker-runtime-release.log全量verify退出0，136项全部通过。内部范围conditional-pass：多槽位不是严格轮次屏障，未来任务大量租户仍有数据库探测负荷；实际网络超时、队列年龄、采集报警、生产压测与故障演练尚缺。

## V010订单解析独立复核（2026-09-07）

独立只读callback_inbox_review核对真实代码与原TC15/16/32合同：RUNNING唤醒不改租约，claim记录代数，READY retry不吞新唤醒；本地未派发关闭持订单→意图→尝试锁，资金/库存/Outbox及任务完成同事务；不以单尝试DONE代替订单终态。未确认阻断性错误释放缺陷，审查未运行测试。

按审查建议补CREATE许可与取消竞争、旧令牌使尝试/意图/库存/Outbox全部回滚，另补并发唤醒及关闭返回收款。最终123项全通过。升级扫描改为全部订单主键有界分页，Java侧只处理正金额未结订单，防大量零元订单使SQL为填满一页扫描过多行。

conditional-pass：内部协议有验证，REVIEW人工恢复/报警、全租户常驻运行、真实供应商可靠关闭及生产容量/容灾仍缺。滚动升级须旧写入和在途事务退出后逐租户从0补漏；共享空库迁移不等于生产升级演练。以下为历史审查。

## 支付恢复编排复核（2026-09-07）

独立只读Agent callback_inbox_review发现P1活性缺口：PaymentDispatchService首次markSubmitted提交后、网络前崩溃，后续仅QUERY，无法补建渠道不存在的订单。新增PaymentChannelAdapter.REPLAYABLE_ABSENCE＋默认关闭supportsSafeCreateReplay；Worker第二个短事务重新检查订单取消／过期／终态和租约，再按同请求号最多补建一次。独立复核认为该路径在渠道已认证前提下修复；审查没有执行测试。

主代理新增故障与合同回归，build-recovery-release.log退出0，109项全通过。旧Worker不能提交许可；网络迟到真实证据独立持久化，任务更新仍受fencing；真实异常金额不在渠道层丢弃。

结论：本地编排conditional-pass；没有真实适配器，能力布尔声明不是供应商永久幂等／关闭墓碑／网络超时认证。风控、常驻调度、取消过期主动唤醒、网络限流／隔离仍待补齐。原始测试证据evidence/payment-recovery；以下为历史审查。

## 2026-09-07：V009 支付回调内部切片（当前状态）

已实现可信适配器之后的密文耐久接收、事件去重、回调任务同事务入队及带租约的本地处理。SQL 使用 CallbackMapper/XML；跨模块使用 PaymentCallbackPort。当前 8 模块、17 Mapper／121 SQL，HTTP 仍为 39 操作／30 路径。

验证命令 `mvn -q -l build-callback-release.log verify` 已退出 0：95 项通过（Foundation83＋其他12），失败／错误／跳过均为 0。原始报告及失败历史在 `.codex/tasks/referral-end-to-end/evidence/callback-inbox/`。覆盖并发去重、接收回滚、旧租约业务回滚、错误业务引用拒绝、密文及归属篡改、密钥轮换／暂缺重试、事务开启失败、真实提交后模拟确认丢失。内部故障注入不等于真实渠道或 HTTP 断响应验收。

曾真实复现连接池耗尽：NEVER 空同步上下文中 Mapper 预读占连接，后续自有事务再取连接。已改为独立短读事务释放后再加密／写事务；保留 10 连接池及 12 线程并发，新增加密阶段无数据源连接绑定的确定性断言。另修复测试中对 MANDATORY 代理直接配置 Mockito 导致的夹具异常，业务事务要求未放宽。独立只读审查已复核关键设计；审查本身没有执行测试。

共享 dev_infra 的 transaction_center 已执行 V009 并 validate 成功，001～009 全成功。含 Flyway 历史表共 29 表／372 字段（业务表 28／362），所有表及字段中文注释缺失均 0；回调、支付尝试、任务均 0 行。没有写入共享测试业务数据或部署应用。V001～V009 冻结，下一迁移 V010；Flyway 对 MySQL 版本验证范围警告仍保留。

范围仍未完成：真实验签／渠道调用／HTTP 应答合同、常驻调度与取消过期唤醒、冲突告警、退款／履约、正式消息、KMS、CI、三高和容灾验收，以及交易完成后的完整裂变改造。详见 [回调合同](../../PAYMENT_CALLBACK_INBOX.md)。以下较早章节为历史切片，不代表当前待实现项。

## 最新任务租约审查

依据long-task-continuity高风险要求，独立只读Agent job_lease_review复核实际jobs、V008、支付入队与升级补漏，未运行测试或写数据库。

确认并修复：滚动升级V008一次回填不能覆盖仍运行旧实例的新尝试；新增PaymentRecoveryBackfillService，并要求旧写入及在途事务退出后按全部租户从零扫描。补漏禁止外层事务，每页10秒READ_COMMITTED，避免旧RR快照／全量长事务。

性能风险修复：跨READY／RUNNING状态排序改为runnable生成槽位等值前缀及有序到期索引；EXPLAIN无filesort通过，不能推导大规模容量达标。领取与续租局部RC，业务默认隔离不变。

令牌复核：先锁任务，再用新的数据库语句时间条件更新；owner／token／状态／未过期均匹配才提交。业务后完成失败使外层回滚，捕获异常也不能提交。新增真实行锁跨过失效点测试通过；Future等待证据未直接查询MySQL锁等待队列，不宣称精确观测每个数据库等待时刻。

final回归79项通过。结论conditional-pass：租约本地基础可继续接入，真实网络旧Worker／耐久资金证据、调度、公平性、告警、人工恢复、退款及全生产验收仍待实现。以下为历史审查。

## 最新支付核心审查与修复

依据long-task-continuity高风险要求，已由独立只读Agent payment_core_review审查实际支付核心及修复。未委托运行测试或数据库写入。其建议补充跨订单复用交易号及关闭／切换／取消三方竞争，已加入FoundationTest。

确认缺陷（高）：PaymentObservationService原先在收款插入前FOR UPDATE锁定不存在交易号，两订单可能互持间隙锁导致死锁；build-payment-final.log实际复现。现为PaymentMapper.xml insertReceipt无操作UPSERT后按渠道交易号当前读，使用receipt_no辨认本次插入，重复资金字段冲突回滚，不依赖affected rows。独立复核未发现新增唯一约束吞错问题。repair及release完整回归均69项通过。

其他复核：prepare拒绝外层事务并先提交本地许可；已保存可靠关闭证明可以完成后来取消；旧证明不能清新尝试；正常资金与订单／库存／首单／账本原子提交；异常资金不重复履约。公共HTTP不能写入支付成功事实。

结论conditional-pass：当前本地切片可继续开发，不能生产收款。尚缺耐久接收／整体事务有界重试、验签、真实渠道可靠屏障、租约fencing、补偿退款及生产容量证据。以下保留历史审查，不将旧“无支付模块”描述用于当前状态。

## 最新会员消费事实切片审查

2026-09-07主Agent自审后，依据long-task-continuity高风险阶段要求启动独立上下文Agent `member_fact_review` 做只读审查（未委托实现或执行数据库写入）。

独立审查未发现本切片阻断缺陷：MemberTradeFactService强制MANDATORY，先事实行锁再永久订单当前读；新增认定／聚合／内部Outbox共事务；XML先判断everPaid再更新它；V006复合外键绑定租户和订单所属会员，首单槽位及版本唯一。

收到三项非阻断测试建议后已补强：并发首单候选回滚后另一事务胜出、跨租户订单写入拒绝，以及隔离SQL验证唯一键／CHECK拒绝非法状态。最终执行结果见QA报告，审查本身不冒充运行测试。

上游真实收款、足额同币种、订单合法状态的校验属于明确的内部Port前置合同，不重复调用他域Mapper。不将这个边界隐瞒成完整支付成功；支付接入时必须新增全链路测试。TC-19／20／29／30及整体裂变仍未完成，不能据局部代码审查批准生产收款。

迁移自审：共享V005没有历史首付事实，增量V006不修改V001～V005；其他环境如存在历史消费必须先评估回填，不猜测补造事实。

## 最新订单切片复核

2026-09-07新增订单模块、V005及7个HTTP操作。核对每个业务SQL租户条件、报价唯一约束、锁序、取消版本条件、金额来源、密文落库及请求脱敏。不存在默认真实商户或默认密钥；地址缺配置失败关闭。

潜在地址幂等碰撞已通过显式字段计算摘要避免：不能调用ShippingAddress.toString，因为该方法脱敏。订单、明细、预占、密文、审计、内部Outbox同事务；测试模拟最后SKU无库存后无残留。

最终mvn -q -l build-order-release-verify.log verify退出0，50项成功。当前局部复核未发现阻断此切片的确认缺陷；整体仍conditional-pass，真实支付／关单／首单／退款等缺失是整体交付阻断。密钥轮换、历史地址受权解密及隐私摘要策略仍需完善后才能生产发布。

以下为此前报价切片复核记录。

## Scope And Diff Base

2026-09-07多SKUHTTP／报价切片。基线为Mapper重构后的工作树，不是已提交Git版本；所有文件仍需保留。由同一Agent分别复核业务、并发、持久化、权限及合同，没有独立跨模型评审。

## Confirmed Findings

| 严重度 | 问题／失败场景 | 当前证据 | 处理 |
|---|---|---|---|
| 中 | 报价首版仅写主明细与幂等结果，缺少统一写审计，无法满足所有写操作可追溯要求 | transaction-catalog/src/main/java/com/lrj/trade/catalog/QuoteService.java:63 | 已补同事务审计，重放只1条、外层回滚0条测试通过 |
| 高，整体范围 | 已有本地订单状态，但尚无真实支付／退款事实，不能验证消费首单或发放裂变奖励 | 当前已有transaction-order，仍无transaction-payment实现；原TC-04～32 | 保持未完成，下一阶段实现，不将报价视为支付事实 |

同时对JSON小数转整数风险显式禁用ACCEPT_FLOAT_AS_INT，并加入小数数量返回400的HTTP回归；原业务整数输入不受影响。

## Rejected Suspicions

| 疑点 | 排除依据 | 证据 |
|---|---|---|
| 重新发布商品会改写旧报价 | 报价保存独立明细，使用旧快照；仅核对商品当前可售 | QuoteService.requireUsable；FoundationTest.quoteUsesFrozenPriceAndExactExpiryBoundary |
| 幂等重试延长有效期 | 已完成Commands返回原引用，不再次调用创建分支 | QuoteService.java:36；到期后同键重放结果完全相等 |
| LAST_INSERT_ID被其他请求覆盖 | 主表插入后立即同一事务连接读取，明细和审计尚未插入；不是跨连接查号 | QuoteService.java:57；多请求幂等及完整明细归属断言 |
| 过期检查在锁等待之前导致失效报价仍可用 | 检查位于全部商品锁定之后，now等于expiresAt即拒绝 | QuoteService.java:81；精确边界测试 |
| Mapper重构遗漏SQL／绑定 | 方法与XML双向一致，绑定与@Param一致 | MapperContractTest；当前10个Mapper／65个操作 |

## Checks Rerun After Fixes

mvn -q -l build-quote-final.log verify退出0，40项成功，无跳过。共享交易库V004执行和Flyway validate成功；17张表／183字段注释完整。

## Residual Risks

- 30个同键请求使用12工作线程执行，是功能并发回归，不是原生产容量目标。
- 报价唯一转订单及本地支付许可屏障已实现，真实渠道关单、首付事实、退款及裂变资格／奖励必须继续。
- 高流量、容灾、真实渠道、字节码架构检查及远程CI缺少完整验收证据。
- 原始生产合同的201／If-Match等差异仍待统一，本切片不宣称正式V1冻结。

## Verdict

conditional-pass：本切片本地实现／回归通过；整体交易及裂变交付未完成，不可据此上线收款或发奖。
