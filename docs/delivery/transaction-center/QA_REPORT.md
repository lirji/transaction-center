## 订单归因阶段完成（2026-09-08）

归因令牌、永久快照、单次消费及Commands淘汰后原成功回放已接入，默认关闭。真实RS256/JWKS、外层事务拒绝、商户锁/地址加密后时间复检、租户/主体隔离、无token兼容、原子回滚及取消不释放均有证据。

验证：组合 `mvn -q -l build-attribution-full.log clean verify` 退出0，428项/34套，失败/错误/跳过均0，包含归因、SDK及自动准入。随后仅收紧3项测试断言，定向补验3项退出0；不把它们相加成431项全量。独立审查关闭同报价掩盖令牌竞争的测试缺口，实际使用两份报价竞争同issuer/jti。证据位于 `.codex/tasks/referral-end-to-end/evidence/order-attribution/`。

共享dev_infra V032迁移及32版validate通过，48张业务表678字段，表/字段中文注释缺失0，归因表0业务行。V001～V032冻结，下一未占用号V033。无应用部署、真实业务开关启用、提交或推送。GitHub Actions新增构建/隔离测试及报告保存，YAML/bash语法和底层本地命令验证；远程CI/安全扫描未执行。

后续并行mapping_adapter交易内部失败关闭映射适配、referral_runtime原营销平台纯规则SPI，属于新切片，**不在上述428全量覆盖内**。真实映射来源已向用户询问，隐私提案、真实渠道和生产参数保持待确认；保留161项及完整裂变/生产门禁。

# QA Report

当前检查点：隔离队列/永久受控重放阶段全量37604退出0，326项、0失败错误跳过，evidence/outbox-replay已封存；独立审查无剩余阻断。共享V023迁移64110退出0，40业务表563字段注释缺失0，Outbox/重放凭证0行；52HTTP/42路径，无部署或真实业务发布。下一步历史维护/告警、对账与完整裂变；后续源码验证状态以PROGRESS为准。下方旧数字仅为历史阶段证据。


## 当前验证基线（覆盖下方历史阶段）

可靠发布关联阶段已验证：mvn -q -l build-outbox-final.log clean verify退出0，320项、22套报告，0失败/错误/跳过；独立审查无剩余阻断。真实KafkaPublisherIT已在独立合并定向中通过（同轮另一个acks断言失败已被本全量修复覆盖），不能把该IT计入默认全量。共享V022已执行并validate通过，39业务表546字段注释缺失0，Outbox与OUTBOX_PUBLISH任务均0行。未启动Worker、未部署应用、未发送真实业务事件。

证据：`.codex/tasks/referral-end-to-end/evidence/outbox-publish`（full/result、独立IT、审查、共享迁移和schema-check）。完整V1现覆盖注册、正常支付、完成/取消、退款证据/成功、库存；原payload事务入队、ACK与fencing原子完成、有界重试隔离和默认关闭均已内部验证。运营重放/告警工单、生产ACL/ISR/配额、对账、C02/C03权威映射及完整裂变仍未完成。

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

## V015最终验证（2026-09-07，当前状态）

V015退款持久Inbox与第四通道已验证。最终mvn -q -l build-refund-inbox-final.log verify（exec22616）退出0，207项全部通过：Foundation165（新增13项退款Inbox）＋RefundChannel11＋其他31，0失败/错误/跳过。共享dev_infra已执行V015（exec43550退出0），15个迁移validate成功；36表/495字段中文注释缺失0，退款证据/退款回调任务0行。22Mapper/170SQL，43HTTP操作/33路径不变。原始11份报告、迁移和巡检及两轮失败报告在.codex/tasks/referral-end-to-end/evidence/refund-inbox。V001～V015冻结，后续V016。无应用部署、共享测试业务写入、其他平台修改或提交推送。

内部切片通过，不等于真实退款渠道或完整裂变验收。REFUND_RECOVER主动查询/同号重放/审批过期、可信审批身份、异常多付应用、独立退货、正式消息资格、真实供应商/生产三高与CI仍须继续。以下运行中/旧版本描述是历史阶段记录。

## 退款渠道边界最终验证（2026-09-07）

退款渠道边界已验证：全量mvn -q -l build-refund-channel-final.log verify（exec58350）退出0，193项（Foundation152＋RefundChannel10＋其他31）全部通过。之后商户号限制128→255并新增兼容测试，最终源码专项mvn -q -l build-refund-channel-compatibility.log '-Dtest=RefundChannelContractTest,QualityGateTest,MapperContractTest' -Dsurefire.failIfNoSpecifiedTests=false test（exec55520）退出0，15项（11＋3＋1）全部通过。不是最终源码单次194项全量；最终修复已按影响面专项复验。原始证据位于.codex/tasks/referral-end-to-end/evidence/refund-channel/{full,final-focused}。无新DDL/HTTP，共享V014，21Mapper/164SQL及43HTTP/33路径不变。真实Adapter/Inbox/Worker、强制网络超时与安全同号重放仍待实现。

以下为历史阶段记录，旧的运行中状态已终结。

## V014最终验证（2026-09-07，当前状态）

V014正常退款申请已通过本地全量回归：`mvn -q -l build-refund-application-final.log verify`（exec45973）退出0，183项测试（Foundation152＋其余31），0失败/错误/跳过，其中新增19项退款申请测试。共享dev_infra已执行V014（exec59200退出0），14个迁移validate成功；35表/469字段中文注释缺失0，四张新增业务表均0行。21Mapper/164SQL、43HTTP操作/33路径；报告在`.codex/tasks/referral-end-to-end/evidence/refund-application/`。V001～V014冻结，后续增量V015。无应用部署、业务种子写入或其他平台改造。实际退款渠道、耐久Inbox/Worker、可信流程审批、退货验收、正式资格证据及生产三高验收仍未完成。

以下保留阶段历史记录；其中“正在验证”“V014未执行”仅描述当时状态。

## V013退款资金前置（2026-09-07，最新已验证切片）

已实现payment拥有的永久退款预占、派发屏障、可靠失败释放、可信成功转账本；所有SQL隔离PaymentRefundMapper/XML，外层MANDATORY事务组合Outbox及末尾fencing。合同见docs/REFUND_FUNDS_CONTRACT.md（相对docs根目录）。没有新增退款HTTP、退款应用、明细分配、审批或实际渠道执行。

最终 `mvn -q -l build-refund-funds-final.log verify` 退出0，164项全部通过：Foundation133（新增14项退款资金）＋Worker10＋RiskProvider4＋其他17，0失败/错误/跳过。首批9项退款＋Mapper/Quality4项也通过；不以该定向结果替代最终全量。原始10份Surefire报告及共享迁移输出在 `.codex/tasks/referral-end-to-end/evidence/refund-funds/`。

验证覆盖：实收10000下20并发各占1000仅10笔成功；永久号重放及跨来源/租户拒绝；外层失败全部回滚；UNKNOWN保留预占；重复成功唯一账本且不改首单/库存；渠道号唯一；派发/本地释放及可信成功/可靠失败竞争；12个不同订单首次预占；CHECK3819/FK1452；旧租约回滚；混合部分退款守恒；跨订单渠道号竞争；异常多付独立退款。以上是内部可信输入测试，不是实际供应商断网、验签或完整退款验收。

共享 `transaction_center` V013 migrate/validate退出0，001～013全成功，31表/415字段中文注释缺失0；退款hold/收款/账本0行。没有共享测试业务数据、其他项目修改、应用部署、提交或推送。首次注释巡检使用中文范围正则被MySQL拒绝（只读，无数据影响），已改Unicode Han属性重检通过。Flyway的MySQL8.4高于已验证8.1警告仍保留。V001～V013冻结，下一V014。

独立只读审查后补成功时间毫秒归一比较、旧账本注释及额外并发用例。内部切片pass，全生产仍conditional-pass：退款应用/明细/正常与大额异常政策/审批/原路恢复/耐久退款Inbox/独立退货/净支付证据、真实风险渠道、KMS、Worker运维、正式事件、CI、三高容灾及完整裂变未完成。下一步按REFUND_IMPLEMENTATION_PLAN.md进入transaction-refund，不将当前资金Port作为完整退款交付。以下保留历史记录。


## V012支付风控门禁（2026-09-07，当前切片）

已实现持久风险历史与精确当前指针、默认WAIT策略、首次及同号补建CREATE有效ALLOW门禁、风险网络事务外、结果与fencing同事务、等待租约锁后的许可终检。ALLOW/DENY后100ms持久重检，WAIT才指数退避；QUERY/CLOSE及真实资金处理不被风险阻断。接口与状态/外部缺口见docs/PAYMENT_RISK_GATE_PLAN.md（相对本文件定位docs根目录）。

最终`mvn -q -l build-payment-risk-final.log verify`退出0，150项全通过（Foundation119＋Worker10＋RiskProvider4＋其他17），0失败/错误/跳过。10份原始报告和共享迁移输出在evidence/payment-risk。新增10项集成/4项Provider合同，包含12并发、SQL/FK约束、旧轮/旧租约、风险网络中取消/接管、真实行锁等待并推进业务Clock后拒绝过期CREATE。首次定向3个夹具错误已修复，失败报告保留；18项定向通过后全量150项再通过。

共享V012 migrate/validate退出0，001～012全成功，30表/394字段中文注释齐全，风险/支付尝试/任务0行；无共享测试业务写入、其他项目修改或应用部署。V001～V012冻结，下一V013；18Mapper/130SQL、39HTTP操作/30路径。内部范围pass，全生产仍conditional-pass：真实风险/渠道供应商、网络时限/配额、KMS、REVIEW运营/采集报警、退款/履约/正式消息/CI/三高容灾与完整裂变尚未完成。下一步按REFUND_IMPLEMENTATION_PLAN.md继续，并保留全部前序缺口。以下为历史记录。


## V011常驻Worker（2026-09-07）

默认关闭的WorkerRuntime、三类固定容量通道、索引租户轮转、整轮工作计数空闲策略、有界停机和低基数进程指标已实现。最终`mvn -q -l build-worker-runtime-release.log verify`退出0，136项全通过（Foundation109＋WorkerRuntime10＋其他17），0失败/错误/跳过。9份原始报告及迁移输出见`.codex/tasks/referral-end-to-end/evidence/worker-runtime/`。

共享V011迁移和validate成功，001～011全成功；29表/374字段中文注释完整，订单/任务0行。只增加带中文注释的调度索引，未写测试业务数据或部署应用。Compose显式worker profile的配置校验通过，普通API固定禁用后台。当前17Mapper/126SQL，HTTP仍39操作/30路径。

新增3项真实MySQL测试覆盖内部发现、终态排除/未来任务、EXPLAIN range索引无filesort及运行时与真实租约联动；10项运行时测试覆盖开关、容量、隔离、轮转、两种空闲吞吐回归、异常恢复和停机。测试Batch不代表真实支付渠道、多实例长稳或三高验收。首次单元编译的submit重载歧义已修复；独立审查确认并修复两次空闲判定P1，中间135项通过后重新136项全量验证。

本切片内部验证pass，全生产仍conditional-pass：队列年龄/采集报警、实际网络超时/风险准入/真实渠道/KMS/退款/履约/正式消息/CI/容量与容灾/外部签署仍缺；全裂变目标未完成。下一步见`docs/PAYMENT_RISK_GATE_PLAN.md`，并保留Worker合同的全部运维缺口。以下为历史记录。


## V010订单解析QA（2026-09-07）

最终 `mvn -q -l build-order-resolution-final.log verify`退出0，123项通过（Foundation106＋其他17），无失败／错误／跳过。环境为隔离Testcontainers MySQL8.4；原始8份报告evidence/order-resolution。前两轮119／123项通过，最终在有界扫描、零元分页和明确推进测试任务到期后再次全量通过。

新增场景覆盖预约、无支付过期、未派发关闭、旧支付DONE后的订单取消、UNKNOWN保留、运行中及退避后唤醒、20并发唤醒、租约回滚、业务与任务原子性、分页补漏、代数CHECK、关闭发现付款和CREATE许可竞争。新增任务类型后旧支付统计限定其原业务类型；没有删除业务断言。

共享V010迁移及validate成功：29表／374字段中文注释完整，订单与任务0行；不在共享库写测试数据或注入故障。没有部署应用。内部范围pass；全生产conditional-pass，真实网络/风控/常驻公平调度/三高长稳/容灾/前端/签署未完成。以下为历史QA。

## 支付恢复编排QA（2026-09-07）

环境：Java21、SpringBoot4.0.2、独立Testcontainers MySQL8.4，测试策略不发真实渠道网络，不修改共享dev_infra业务数据。

实际完整命令 `mvn -q -l build-recovery-release.log verify`退出0，109项通过／0失败／0错误／0跳过。Foundation92＋其他17。证据evidence/payment-recovery的8份原始报告。覆盖网络无事务／连接、未知重试、耐久交接、旧租约、迟到资金、可靠关闭、缺适配器、同号补建及查询后取消／接管；协议单元覆盖重复策略、错误绑定、未经认证补建和证据接收失败。

修复历史：首次新测试cancel参数缺少reason导致编译失败；第二次108项中测试SQL列名错误导致1错误；纠正merchant_request_no并增加二次许可接管断言后109项通过，未削弱断言。另修复独立审查确认的首次未发送恢复活性缺口。

结论：内部范围pass，全生产范围conditional-pass。没有实际HTTP签名／供应商超时和关闭证明、风险决策、吞吐／长稳／容灾／前端或外部签署。以下历史QA不替代本轮结果。

## 2026-09-07：V009 支付回调内部切片（当前状态）

已实现可信适配器之后的密文耐久接收、事件去重、回调任务同事务入队及带租约的本地处理。SQL 使用 CallbackMapper/XML；跨模块使用 PaymentCallbackPort。当前 8 模块、17 Mapper／121 SQL，HTTP 仍为 39 操作／30 路径。

验证命令 `mvn -q -l build-callback-release.log verify` 已退出 0：95 项通过（Foundation83＋其他12），失败／错误／跳过均为 0。原始报告及失败历史在 `.codex/tasks/referral-end-to-end/evidence/callback-inbox/`。覆盖并发去重、接收回滚、旧租约业务回滚、错误业务引用拒绝、密文及归属篡改、密钥轮换／暂缺重试、事务开启失败、真实提交后模拟确认丢失。内部故障注入不等于真实渠道或 HTTP 断响应验收。

曾真实复现连接池耗尽：NEVER 空同步上下文中 Mapper 预读占连接，后续自有事务再取连接。已改为独立短读事务释放后再加密／写事务；保留 10 连接池及 12 线程并发，新增加密阶段无数据源连接绑定的确定性断言。另修复测试中对 MANDATORY 代理直接配置 Mockito 导致的夹具异常，业务事务要求未放宽。独立只读审查已复核关键设计；审查本身没有执行测试。

共享 dev_infra 的 transaction_center 已执行 V009 并 validate 成功，001～009 全成功。含 Flyway 历史表共 29 表／372 字段（业务表 28／362），所有表及字段中文注释缺失均 0；回调、支付尝试、任务均 0 行。没有写入共享测试业务数据或部署应用。V001～V009 冻结，下一迁移 V010；Flyway 对 MySQL 版本验证范围警告仍保留。

范围仍未完成：真实验签／渠道调用／HTTP 应答合同、常驻调度与取消过期唤醒、冲突告警、退款／履约、正式消息、KMS、CI、三高和容灾验收，以及交易完成后的完整裂变改造。详见 [回调合同](../../PAYMENT_CALLBACK_INBOX.md)。以下较早章节为历史切片，不代表当前待实现项。

## 最新任务租约QA

2026-09-07，`mvn -q -l build-job-final.log verify`退出0：79项全通过（Foundation71＋其他8），0失败／错误／跳过。环境为Testcontainers隔离MySQL8.4与localhost，公共库未注入故障。

新增10用例与JL-01～09对应见docs/JOB_LEASES.md。并发24任务／12工作者无重复；旧token完成使支付资金／订单／库存／会员事实回滚；异常被捕获仍rollback-only；租约到期后的同owner必须接管新token；延迟及REVIEW不释放库存。补漏保留DONE，分页和跨租户边界通过。到期扫描EXPLAIN命中idx_job_due且无filesort。

缺陷记录：初次编译辅助receive参数类型错误已修复；verify2的SKIP LOCKED测试锁定夹具遗漏job_type导致锁范围过宽，完整唯一键定位后保留原断言并通过。历史失败报告在evidence/job-leases/verify2-failure.txt，不覆盖或隐藏。

独立审查补强滚动升级收尾和每页独立事务，以及锁等待跨期测试。后者保持真实行锁1.5秒跨过1秒到期点，等待中的续租／完成释放后均拒绝；未直接采集MySQL等待队列，不能据此声称完整生产故障交错证明。

V008共享迁移及validate成功，28表／347字段中文注释完整，任务和尝试均0行。无真实渠道、生产滚动升级、有历史数据全量迁移、容量或容灾验证。结论conditional-pass，整体交易／裂变未完成。

## 最新支付核心QA

2026-09-07，`mvn -q -l build-payment-release.log verify`退出0，69项全部通过（Foundation61＋其余8），没有失败、错误或跳过。环境为localhost随机端口和Testcontainers隔离MySQL8.4，不在共享库注入故障。

测试覆盖及方法入口见FoundationTest新增12项和docs/PAYMENT_CORE.md；覆盖支付重放、两笔实收仅一次正常确认、错金额／币种保留、跨租户／所有权拒绝、外层失败全域回滚、同会员并发首单、关闭／收款竞争、旧关闭证明不清新尝试。100次重放使用12线程池，不是生产吞吐测试。

缺陷与复测：build-payment-final.log曾有1项并发首收款死锁错误。修复缺失键锁顺序后，repair及release均69项成功。修复保留真实资金唯一性和重放冲突核验，没有删减断言。

V007共享迁移与validate退出0，27表／332字段中文注释完整；5张新增表均0业务行。证据保存在.codex/tasks/referral-end-to-end/evidence/payment-core/。仅批准范围内DDL，无生产部署。

结论conditional-pass：局部机制通过；原始回调耐久应答、真实验签／渠道、Worker崩溃恢复、退款、生产容量及裂变联调未验证。以下为历史切片记录。

## 最新会员消费事实切片

最终补强重跑：`mvn -q -l build-member-facts-release.log verify`退出0，57项全部通过（FoundationTest49、QualityGate3、MapperContract1、JwtValidation1、OpenApi1、AddressProtection2）。包含下表首轮用例及新增rolledBackFirstCandidateDoesNotPreventConcurrentWinner、paidFactDatabaseConstraintsRejectCorruption。没有跳过或残留失败，进程已结束。

`mvn -q -l build-member-facts.log verify`退出0，共55项全部通过；真实隔离MySQL8.4，未使用共享业务库作测试。5项新增FoundationTest见docs/MEMBER_TRADE_FACTS.md及surefire报告：

| 场景 | 用例 | 结果 |
|---|---|---|
| 两订单并发、100次重放、迟到本地时间不换首单 | paidOrderFactsSerializeFirstAndKeepOriginalOnLateConfirmation | pass，12线程，非压测 |
| 外层失败后无消费或Outbox残留；无事务拒绝 | paidOrderFactsRollbackWithCallerAndRequireTransaction | pass |
| 可重复读旧快照不绕過订单去重 | paidOrderFactReplayUsesCurrentReadAfterOldSnapshot | pass |
| 零／负／越界金额、币种、跨租户／订单归属、冲突重放 | paidOrderFactsRejectZeroConflictingMoneyAndForeignOwnership | pass |
| 会员冻结后合法资金事实仍可认定 | frozenMemberStillAcceptsAuthoritativePaidFacts | pass，内部端口 |

结论：内部切片conditional-pass。真实回调验签、资金账本、订单／库存同事务编排、退款及新客证据查询未实现，不以这些测试替代完整TC或生产验收。

独立审查后补充并发候选回滚、跨租户订单写入和数据库唯一键／CHECK负向用例。第二轮 `build-member-facts-final.log` 有1项测试失败：MySQL已返回3819拒绝非法聚合，但Spring包装为UncategorizedSQLException，原断言误期望DataIntegrityViolationException。修复仅调整测试为核对最具体SQLException错误码（唯一键1062、CHECK3819），不放宽业务或数据库约束。

## 最新订单切片结果

环境仍为隔离MySQL8.4及localhost随机HTTP端口。mvn -q -l build-order-release-verify.log verify退出0，共50项全部成功，无跳过。

| 用例 | 证据 | 结果 |
|---|---|---|
| 有效旧报价实际下单、地址只存密文、同键改地址冲突 | physicalOrderFreezesQuoteAndEncryptsAddress | pass |
| 最后SKU缺货回滚订单、全部预占及流水 | multiSkuOrderFailureRollsBackAllReservationsAndSnapshots | pass |
| 支付许可／取消竞争只到一个安全结果 | orderCancelAndPaymentPermissionSerializeOnSameRow | pass，本地许可层 |
| 过期且有许可只CLOSING，无许可取消才释放 | pendingPaymentCancelNeverReleasesStockAndNoPaymentCancelDoes | pass，真实渠道层待实现 |
| 零元纯服务不收款、不设置paidAt、不占首单 | serviceZeroOrderNeedsNoAddressAndNeverBecomesFirstPaidOrder | pass |
| 商户冻结、越权、过期报价拒绝 | orderOwnershipFrozenMerchantAndExpiredQuoteFailClosed | pass |
| 100同键请求（12工作线程）只有一单一组预占 | oneHundredOrderReplaysHaveOneBusinessEffect | pass，非容量测试 |
| 商户管理及订单HTTP、金额篡改拒绝、地址不泄露 | merchantAndOrderHttpFlowPreservesOwnershipAndHidesAddress | pass |
| 缺密钥失败关闭、随机Nonce和密文AAD防替换 | AddressProtectionTest两项 | pass |
| 共享V005迁移／注释 | Flyway migrate+validate，21表／244字段无缺注释 | pass，无测试业务数据 |

整体仍conditional-pass；支付网络故障、履约、退款、密钥轮换、生产容量及裂变发奖均未验收。以下保留报价阶段的历史案例。

## Environment Profile

- 目标：Spring Boot随机localhost端口、Testcontainers隔离mysql:8.4。
- 版本：2026-09-07未提交工作树；Maven Java21／Spring Boot4.0.2，MyBatis Starter4.0.1。
- 数据：每案例独立租户，写入隔离关系库；HTTP权限测试使用测试范围JwtDecoder，另有真实RSA／JWKS校验测试。
- 共享库：仅执行已授权V004及只读注释核对，不注入故障／测试业务数据。

## Cases

| ID | 范围 | 操作与预期 | 实际证据 | 结果 |
|---|---|---|---|---|
| Q-01 | AC-02幂等 | 30同键请求（12线程）及数组换序，只1报价／审计；改数量409 | quoteIsConcurrentIdempotentAndCanonicalBySku | pass |
| Q-02 | AC-02价格 | 下架拒绝使用；重新定价发布后，有效旧报价仍按旧价 | quoteUsesFrozenPriceAndExactExpiryBoundary | pass，订单部分未覆盖 |
| Q-03 | AC-02过期 | expiresAt前1毫秒可用，相等时拒绝；重放不续期 | 同上，替换Clock而非sleep | pass |
| Q-04 | AC-01/02权限 | 未注册拒绝；同租户他人／跨租户404；冻结禁止新报价但允许原结果查询 | quoteOwnershipAndFrozenMemberAreEnforced | pass |
| Q-05 | AC-02金额 | 空／重复／超限SKU、数量、混币、乘法及总额越界拒绝且无残留 | invalidQuoteAmountsAndSelectionsLeaveNoPartialRows | pass |
| Q-06 | AC-02原子性 | 外层事务报错，报价、明细、幂等及审计全部回滚 | quoteSnapshotAndCommandRollbackWithCaller | pass |
| Q-07 | AC-02/09 HTTP | 管理授权、多规格增改发布、本人报价查询；金额篡改、小数和未知字段400 | skuAndQuoteHttpContractRejectsTamperingAndFractionalQuantities | pass |
| Q-08 | AC-09合同 | 当前28操作／22路径与控制器对应；Mapper参数／XML、注释和模块源代码约束 | OpenApiTest、MapperContractTest、QualityGateTest | pass |
| Q-09 | AC-07迁移 | V004隔离测试后执行共享交易专属库，validate成功且注释完整 | Flyway命令退出0，17表／183字段缺注释0 | pass |

## Defects And Retests

补齐报价事务审计并完成最后全量回归。HTTP小数数量检查验证了显式禁用整数截断配置，返回400而非静默少买。

## Automated Regression

mvn -q -l build-quote-final.log verify：退出0，FoundationTest34、QualityGateTest3、MapperContractTest1、JwtValidationTest1、OpenApiTest1，共40项，0失败／0错误／0跳过。临时MySQL已回收。

## Blocked External Checks

真实身份／渠道合同、生产容量／容灾、跨项目奖励／追偿和Cursor前端验收尚无完整证据。当前仍有可独立实施的订单等内容，不构成停止开发的阻塞。

## Verdict

conditional-pass：多SKU及报价本地切片通过；交易及裂变完整验收未完成。原TC-04／TC-05包括订单验证，不得用报价端口测试代替整个案例。
