> 证据可用性：本文为累计历史验证记录；`.codex/`、本机日志和绝对路径未随 Git 上传，远程克隆不能直接取得它们。本次提交快照的可携带验证摘要及源码指纹见 [Git 交付记录](GIT_DELIVERY.md)。历史全量不代表当前工作树全量，远程 CI 结果须另外核验。

> 最新验证索引（2026-09-09）：后端报告汇总451项（全量verify后SDK独立复验）、前端49项、lint/构建及9项机器合同测试通过。详细范围见 [当前状态](PROJECT_STATUS.md) 与 [提交前证据](delivery/admin-query-apis/prepush-verification.json)。演示库V033已应用并校验，应用已本机部署；下文各阶段数字及“未部署/未推送”只描述当时现场。

## 订单归因阶段完成（2026-09-08）

归因令牌、永久快照、单次消费及Commands淘汰后原成功回放已接入，默认关闭。真实RS256/JWKS、外层事务拒绝、商户锁/地址加密后时间复检、租户/主体隔离、无token兼容、原子回滚及取消不释放均有证据。

验证：组合 `mvn -q -l build-attribution-full.log clean verify` 退出0，428项/34套，失败/错误/跳过均0，包含归因、SDK及自动准入。随后仅收紧3项测试断言，定向补验3项退出0；不把它们相加成431项全量。独立审查关闭同报价掩盖令牌竞争的测试缺口，实际使用两份报价竞争同issuer/jti。证据位于 `.codex/tasks/referral-end-to-end/evidence/order-attribution/`。

共享dev_infra V032迁移及32版validate通过，48张业务表678字段，表/字段中文注释缺失0，归因表0业务行。当时V001～V032冻结，下一迁移号为V033；V033现已实施，见顶部最新索引。无应用部署、真实业务开关启用、提交或推送。GitHub Actions新增构建/隔离测试及报告保存，YAML/bash语法和底层本地命令验证；远程CI/安全扫描未执行。

后续并行mapping_adapter交易内部失败关闭映射适配、referral_runtime原营销平台纯规则SPI，属于新切片，**不在上述428全量覆盖内**。真实映射来源已向用户询问，隐私提案、真实渠道和生产参数保持待确认；保留161项及完整裂变/生产门禁。

追踪阶段403项/31套全量通过；共享V031已完成（47表659字段中文注释完整）。错误SDK4项单测、9项合同脚本检查通过。自动准入首轮21项含真实数据库受理/回调/退款边界通过，时钟与滞回修复后16项纯单元及门禁通过，独立复审无阻断；403全量不包含之后新增的SDK和自动准入。完整裂变及生产验收继续。

# 首批验证记录

当前检查点：隔离队列/永久受控重放阶段全量37604退出0，326项、0失败错误跳过，evidence/outbox-replay已封存；独立审查无剩余阻断。共享V023迁移64110退出0，40业务表563字段注释缺失0，Outbox/重放凭证0行；52HTTP/42路径，无部署或真实业务发布。下一步历史维护/告警、对账与完整裂变；后续源码验证状态以PROGRESS为准。下方旧数字仅为历史阶段证据。


## 历史验证基线（覆盖当时下方历史阶段）

可靠发布关联阶段已验证：mvn -q -l build-outbox-final.log clean verify退出0，320项、22套报告，0失败/错误/跳过；独立审查无剩余阻断。真实KafkaPublisherIT已在独立合并定向中通过（同轮另一个acks断言失败已被本全量修复覆盖），不能把该IT计入默认全量。共享V022已执行并validate通过，39业务表546字段注释缺失0，Outbox与OUTBOX_PUBLISH任务均0行。未启动Worker、未部署应用、未发送真实业务事件。

证据：`.codex/tasks/referral-end-to-end/evidence/outbox-publish`（full/result、独立IT、审查、共享迁移和schema-check）。完整V1现覆盖注册、正常支付、完成/取消、退款证据/成功、库存；原payload事务入队、ACK与fencing原子完成、有界重试隔离和默认关闭均已内部验证。运营重放/告警工单、生产ACL/ISR/配额、对账、C02/C03权威映射及完整裂变仍未完成。

## 审批真实密码学HTTP联测（2026-09-07）

新增真实访问JWT签名＋独立审批签名，经HTTP安全过滤链到MySQL审批原子受理的联测；测试Bean仅委托真实Nimbus，不提供预制身份或批准。两个JWKS密钥集合独立，访问密钥冒签回执/伪签名/跨租户/错误访问audience拒绝；成功重复同编号、一次证据/人工审计。最终exec30570专项13项退出0，6份原报告在evidence/approval-real-http。

仅测试及文档变化，生产代码基线仍此前240项全量通过，本次不是单次241全量。无运行进程/迁移/部署，共享V017；生产HTTPS/真实流程签署/安全拒绝审计/网络预算仍未完成。下一步拒绝安全审计，保留全部交易、异常退款/退货/资格及完整裂变目标。

## 审批HTTP最终验证（2026-09-07）

固定verify→accept、专用trade.refund.approval作用域、默认关闭及固定HTTPS/JWKS/专属audience/服务主体已接通。exec52443（mvn -q -l build-approval-http-final.log verify）退出0，240项全通过，0失败/错误/跳过；14份原报告在evidence/approval-http。独立只读审查无确定越权漏洞，报文边界/缺密钥503/关闭MVC503/拒绝无副作用均补验。Compose静态config --quiet退出0，未部署；Worker强制关闭审批入口。

45HTTP操作/35路径，23Mapper/175SQL，共享V017不变，本轮无数据库迁移或共享业务写入。HTTP集成Mock Verifier，密码学由真实RSA独立验证；默认关闭是配置上下文＋独立MVC测试，不能把这些说成真实供应商HTTP密码学全链路。后续统一真实RSA/HTTP/DB联测、拒绝安全审计/告警、真实流程配置/签署与密钥预算仍保留；异常退款/退货/正式资格/三高和完整裂变未完成。下文运行中及旧状态是历史记录。

## V017审批原子受理最终验证（2026-09-07）

同步密文证据与审批/资金组件拒绝释放/审计/Outbox/持久唤醒原子受理已实现，无HTTP写入口。最新exec23123（mvn -q -l build-approval-receipt-final.log verify）退出0，236项全部通过，0失败/错误/跳过，含7个approvalReceipt用例和真实RSA回执校验。独立只读审查无确定安全缺陷，建议的跨租户事件、末尾过期回滚及合法拒绝重复均已补验。

共享dev_infra已执行V017，exec39048退出0，17版validate成功；36张业务表/497字段中文注释缺失0，审批证据0行。原始13份测试报告、迁移巡检和审查位于evidence/approval-receipt。23Mapper/175SQL、44HTTP操作/34路径。V001～V017冻结，后续V018。无部署/共享业务测试数据/其他平台修改/提交推送。

后续仍须固定verify→accept的专用scope默认关闭HTTP、生产流程身份与密钥配置、错误安全审计；异常退款/退货/正式资格事件及原生产门禁和完整裂变均未完成。以下旧状态仅为历史记录。

## 可信审批认证前置（2026-09-07）

RefundApprovalVerifier/VerifiedApprovalReceipt已实现但未启用，无HTTP/Bean/资金调用。固定服务身份和专用签名回执绑定租户、真实审批人、版本、引用与5分钟窗口。独立审查发现双时钟不一致，修复后固定Clock和精确边界测试通过。最终exec39760专项9项退出0，报告evidence/refund-approval-verifier；不是完整审批受理或最新全量证明。

下一步按REFUND_APPROVAL_RECEIPT.md实现V017密文Inbox/永久事件去重/冲突/原子受理、专用scope和默认拒绝配置。共享V016，HTTP44/34、Mapper22/173不变，原生产及完整裂变范围保留。

## 后台退款队列（2026-09-07）

GET /api/v1/trade/admin/refunds已实现，trade.admin作用域、JWT租户、固定状态/主键有界分页，最小脱敏视图；不开放审批写入。exec37077专项退出0，共7项：Foundation2＋Mapper1＋Quality3＋OpenAPI1，0失败/错误/跳过。报告evidence/refund-admin-queue。当前22Mapper173SQL、44HTTP34路径，无DDL/共享写入/应用部署。此前223全量及追加22专项是原路绑定基线，本次仅声明受影响专项通过，不冒充最新全量225。

详情和下一可信审批接收边界见REFUND_ADMIN_QUEUE.md。通用后台权限不等于可信流程签名，审批身份/结果合同仍须实现，保留异常补偿/退货/正式资格/完整裂变和生产门禁。下文旧状态为历史记录。

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


## 最新：2026-09-07 V010订单解析及持久唤醒

最终源码 `mvn -q -l build-order-resolution-final.log verify`退出0：123项全部通过（Foundation106＋其他17），0失败／错误／跳过。8份原始报告在evidence/order-resolution。前两轮119、123项也通过；最终补强有界主键扫描、零元游标推进和独立于机器日期的到期夹具后再次全通过，非仅沿用旧结果。

新增14项集成用例覆盖：创建预约、提前投递重检、无支付过期释放、未派发本地证明关闭、旧支付DONE后取消、UNKNOWN保留库存与事务外CLOSE、唤醒前后两种提交顺序、20并发唤醒与退避、旧令牌回滚、预约／唤醒失败业务回滚、旧订单分页补漏、代数CHECK3819、关闭查询返回收款、CREATE许可与取消竞争、尝试／意图／库存／Outbox一起回滚。已有零元用例增加无订单任务及分页继续前进断言。新增订单任务后，原支付任务断言显式限定PAYMENT_RECOVER，不混计不同任务类型。

独立只读复核覆盖代数与资金关闭路径，没有确认阻断性资金释放缺陷；实际测试由主代理执行。本地未派发证明不替代在途渠道可靠终态。真实渠道、常驻调度、人工REVIEW恢复／告警、全三高容灾仍未完成。

共享dev_infra交易库V010迁移及validate退出0：001～010全成功，29表／374字段中文注释缺失0，订单及任务0行。仅授权交易库增量操作，无测试数据或应用部署；V001～V010冻结，后续V011。Flyway MySQL版本验证范围警告保留。17Mapper／125SQL、39HTTP操作／30路径。以下为历史记录。

## 最新：2026-09-07 支付恢复编排

`mvn -q -l build-recovery-release.log verify`退出0，109项全通过：Foundation92＋QualityGate3＋MapperContract1＋JwtValidation1＋OpenApi1＋AddressProtection2＋CallbackProtection4＋PaymentChannelContract5。失败／错误／跳过均0。原始8份报告在 `.codex/tasks/referral-end-to-end/evidence/payment-recovery/`。

新增9个集成测试和5个协议测试覆盖事务外网络无连接绑定、UNKNOWN原号查询、耐久证据异步入账、失效许可回滚、网络期间接管保留证据、可靠关单、缺适配器等待、崩溃前未发送的同号补建、查询期间取消和二次许可前接管。固定测试策略不是实际供应商，未证明真实签名、超时或永久幂等。

首轮编译因新测试取消参数缺少reason失败；补齐后108项中1项测试SQL误用request_no列，按实际merchant_request_no修复，另增加二次许可接管测试后最终109通过。失败原始记录保留，未放宽业务断言。

独立只读审查发现P1：首次许可提交但未发请求后永久QUERY无补建。已补默认关闭的认证能力协议REPLAYABLE_ABSENCE，补建前重检取消／过期／租约，每次最多一次且不换号。复核通过不代表真实供应商合同已认证。没有DDL或共享库写入，仍V009；17Mapper／121SQL及39HTTP操作不变。参见PAYMENT_RECOVERY_WORKER.md。以下为历史切片。

## 2026-09-07：V009 支付回调内部切片（当前状态）

已实现可信适配器之后的密文耐久接收、事件去重、回调任务同事务入队及带租约的本地处理。SQL 使用 CallbackMapper/XML；跨模块使用 PaymentCallbackPort。当前 8 模块、17 Mapper／121 SQL，HTTP 仍为 39 操作／30 路径。

验证命令 `mvn -q -l build-callback-release.log verify` 已退出 0：95 项通过（Foundation83＋其他12），失败／错误／跳过均为 0。原始报告及失败历史在 `.codex/tasks/referral-end-to-end/evidence/callback-inbox/`。覆盖并发去重、接收回滚、旧租约业务回滚、错误业务引用拒绝、密文及归属篡改、密钥轮换／暂缺重试、事务开启失败、真实提交后模拟确认丢失。内部故障注入不等于真实渠道或 HTTP 断响应验收。

曾真实复现连接池耗尽：NEVER 空同步上下文中 Mapper 预读占连接，后续自有事务再取连接。已改为独立短读事务释放后再加密／写事务；保留 10 连接池及 12 线程并发，新增加密阶段无数据源连接绑定的确定性断言。另修复测试中对 MANDATORY 代理直接配置 Mockito 导致的夹具异常，业务事务要求未放宽。独立只读审查已复核关键设计；审查本身没有执行测试。

共享 dev_infra 的 transaction_center 已执行 V009 并 validate 成功，001～009 全成功。含 Flyway 历史表共 29 表／372 字段（业务表 28／362），所有表及字段中文注释缺失均 0；回调、支付尝试、任务均 0 行。没有写入共享测试业务数据或部署应用。V001～V009 冻结，下一迁移 V010；Flyway 对 MySQL 版本验证范围警告仍保留。

范围仍未完成：真实验签／渠道调用／HTTP 应答合同、常驻调度与取消过期唤醒、冲突告警、退款／履约、正式消息、KMS、CI、三高和容灾验收，以及交易完成后的完整裂变改造。详见 [回调合同](PAYMENT_CALLBACK_INBOX.md)（delivery 子目录请定位 docs 下同名文件）。以下较早章节为历史切片，不代表当前待实现项。

## 最新：2026-09-07 可恢复任务租约

当前源码 `mvn -q -l build-job-final.log verify`退出0，Foundation71＋QualityGate3＋MapperContract1＋JwtValidation1＋OpenApi1＋AddressProtection2，共79项全部通过，0失败／错误／跳过。8模块、16Mapper／116SQL，HTTP仍39操作／30路径。原始报告位于.codex/tasks/referral-end-to-end/evidence/job-leases/。

新增10项覆盖：支付／任务原子入队、终态重放不重启、24任务12工作者批量互斥领取、租户隔离、过期接管、同owner旧token拒绝、旧令牌导致收款／库存／首单／账本回滚、捕获异常仍rollback-only、退避／REVIEW保留资源、SKIP LOCKED、数据库时钟及CHECK、升级分页补漏、EXPLAIN到期索引且无filesort、持行锁跨过期限后续租及完成拒绝。

首轮build-job-verify.log为测试辅助方法参数类型编译错误，已修复；verify2共78项中1项夹具锁范围断言失败（锁定SQL遗漏job_type，扫描锁超出单任务），补全唯一键条件后最终79项通过。未删除或弱化并发断言。

独立审查发现滚动升级旧实例可能在V008回填后产生无任务尝试，已补PaymentRecoveryBackfillService；强制每页事务外调用、10秒READ_COMMITTED事务。必须在旧写入及在途事务退出后从零扫全部租户。测试模拟缺任务尝试并验证幂等补漏，不等于已演练生产滚动发布。

共享V008 migrate＋validate退出0；001～008全成功，28表／347字段中文注释缺失0，payment_attempts和jobs均0行。旧迁移冻结，下一V009；无其他项目修改或生产部署。Flyway版本验证范围警告仍存在。

当前只是队列及租约协议，未运行真实渠道Worker、耐久回调、取消／过期唤醒、退款、完整TC-32或三高验证。以下为历史切片记录。

## 最新：2026-09-07 支付核心回归与共享V007

当前源码执行 `mvn -q -l build-payment-release.log verify` 退出0：Foundation61、QualityGate3、MapperContract1、JwtValidation1、OpenApi1、AddressProtection2，共69项全部通过，0失败／错误／跳过。7模块、15Mapper／108SQL、39HTTP操作／30路径。原始报告归档于 `.codex/tasks/referral-end-to-end/evidence/payment-core/`。

新增12项覆盖支付幂等受理、稳定请求号、UNKNOWN查询计划、可靠关闭前持有库存、正常／异常资金、100次重放、原子回滚、冻结后收款、并发首单、跨订单交易号冲突、三方关闭切换取消竞争、HTTP权限和金额篡改。内部可信观察测试不等同真实渠道回调、压测或商业履约验收。

曾在 `build-payment-final.log` 复现两订单并发首次收款的MySQL间隙锁死锁；先UPSERT再当前读、用receipt_no区分重放后，`build-payment-repair.log`及本次release回归均通过69项。未靠放宽测试或重跑掩盖缺陷；不宣称消除全部死锁。

共享dev-infra-mysql84-1的transaction_center执行V007并validate成功（退出0），001～007全部成功。27表／332字段中文注释缺失均0，新增5表业务行数均0。未部署应用或修改其他项目。V001～V007已冻结，后续从V008开始。Flyway的MySQL版本验证范围警告仍存在。

剩余：耐久原始回调、验签／加密、真实渠道及风控、租约Worker、退款／退货、正式事件、完整生产与裂变联调。以下各节为历史切片证据。

## 最新：2026-09-07 会员不可逆消费事实内部端口

最终补强回归：`mvn -q -l build-member-facts-release.log verify`退出0，FoundationTest49＋其他8＝57项全部通过，无失败／错误／跳过。比首轮新增并发首候选回滚和数据库唯一键／CHECK直接拒绝测试，同时增加跨租户写入场景；中间断言修复记录见QA_REPORT。独立上下文只读审查已完成，未发现本切片阻断缺陷。

`mvn -q -l build-member-facts.log verify`退出0：FoundationTest47、QualityGateTest3、MapperContractTest1、JwtValidationTest1、OpenApiTest1、AddressProtectionTest2，共55项，0失败／0错误／0跳过。隔离MySQL8.4从空库执行V001～V006成功，表／字段注释检查通过。当前13个Mapper／83个SQL，HTTP仍35操作／27路径。

新增5项：两订单并发与100次重放（12线程池）、首单在时钟回拨后不换、外层回滚及MANDATORY边界、MVCC旧快照后当前读去重、正金额／币种冲突及订单会员复合FK、冻结后仍认定已发生消费。详情见[消费事实合同](MEMBER_TRADE_FACTS.md)。

这是上游收款编排的内部前置测试，未连接真实渠道，不证明TC-19／20／29／30整体通过；没有新增用户提交支付事实的入口。原冻结保留消费测试从非法裸写改为真实关联订单的端口fixture，原隔离和不可逆断言保留。

共享库V006执行及Flyway validate成功，001～006均成功；22张表／259字段注释缺失均0，tc_member_paid_order行数0。仅交易专属库DDL，无测试业务数据写入。Flyway仍提示MySQL8.4超出其验证到8.1的范围，不能把成功执行当作消除了兼容性风险。

完整原TC／INFRA逐项状态见[验收覆盖账本](ACCEPTANCE_COVERAGE.md)。原始Maven报告快照保存在.codex/tasks/referral-end-to-end/evidence/member-facts/，目标档案检查不会把部分证据升级为全目标通过。

## 最新：2026-09-07 订单与库存组合事务

`mvn -q -l build-order-release-verify.log verify`退出0，FoundationTest42、QualityGateTest3、MapperContractTest1、JwtValidationTest1、OpenApiTest1、AddressProtectionTest2，共50项全部通过，0失败／0错误／0跳过。35个HTTP操作／27个路径与OpenAPI对应；Compose config --quiet验证退出0。

覆盖有效旧报价实际下单锁价、多SKU最后一项缺货全部回滚、100次同键下单仅一组副作用（12工作线程，不是100线程压测）、支付许可与取消竞争20请求、未决支付取消不释放、无许可取消释放、零元服务不占首单、商户冻结、越权、实物地址AES-GCM解密校验及跨租户／订单／字段密文替换失败、HTTP金额篡改拒绝及地址不泄露。

最终测试后执行V005到dev-infra-mysql84-1的transaction_center，迁移与validate均成功。001～005均成功；21张表／244字段缺注释0；共享订单和商户行数均为0，无测试业务数据写入。没有修改现有其他项目、部署常驻应用或提交／推送Git。

尚未覆盖真实支付网络交错、收款／退款／履约／首单事实、千并发下单及生产容量、密钥轮换和受权历史地址解密。不能把本地许可屏障当作渠道可靠关单证明。

## 最新：2026-09-07 多SKU与服务端报价

`mvn -q -l build-quote-final.log verify`退出码0。FoundationTest34、QualityGateTest3、MapperContractTest1、JwtValidationTest1、OpenApiTest1，共40项全部通过，0失败／0错误／0跳过。28个HTTP操作／22个路径与OpenAPI一致。

新增证据覆盖30个同键报价请求、数组换序幂等、快照锁价、下架拒绝、到期精确边界、冻结与归属、整数溢出／混币／数量边界、外层事务回滚、审计唯一、HTTP未知字段与小数拒绝。报价不改库存；订单尚未实现，因此不标记TC-04／TC-05整体通过。

测试完成后，使用scripts/MigrateDatabase.java在dev-infra-mysql84-1的transaction_center执行V004，退出0且Flyway validate成功。001～004均成功；17张表／183字段，缺失注释均为0。共享tc_quote行数为0，未导入测试数据；既有其他项目未修改。

MySQL8.4的Flyway已验证版本警告仍保留。以下为历史切片结果，最新计数以本节为准。

## 最新：2026-09-07 Mapper隔离回归

执行 `mvn -q -l build-mapper-final.log verify`，退出码0。FoundationTest28、QualityGateTest3、MapperContractTest1、JwtValidationTest1、OpenApiTest1，共34项全部通过，0失败／0错误／0跳过。

- 9个Mapper／XML覆盖60条SQL；规范化绑定占位、结果别名及空白后与抽离前逐条一致。
- XML与接口方法双向匹配，@Param与SQL绑定一致；禁止文本替换、无类型参数和二级缓存。生产服务不依赖JDBC、不内嵌SQL。
- 外层事务失败同时回滚会员、消费事实、幂等命令、审计、Outbox；同一事务其他适配器写入后重复锁定读，读取新版本而非会话缓存。
- UTC毫秒、nullable版本、最大安全金额、含引号文本、规格JSON及草稿／发布快照映射通过；原并发注册、防超卖、终态竞争、权限和幂等回归保留。
- 22个HTTP操作／17个路径合同不变；Java只读模型移至model包，仓库引用已同步。
- 测试仅使用隔离MySQL8.4，执行工作区V001～V004并核验注释；共享dev_infra本轮无DDL、无测试数据写入，仍为001～003。

本轮由同一Agent分别进行业务／并发／持久化复核，未进行跨模型评审；未发现本次重构阻断项。这不代替完整生产验收。Flyway对MySQL8.4验证版本的警告仍存在，无生产压测、容灾及真实支付认证证据。

以下为历史切片证据，不作为当前最新测试计数。

日期：2026-09-07。本记录对应首批内部模块，不是全交易中心验收报告。

## 最终执行结果

- `mvn -q -l build-final.log verify`：退出码0；5模块构建成功，输出可运行Spring Boot JAR。
- FoundationTest：14测试，0失败／0错误／0跳过。真实MySQL8.4迁移、表字段注释、CHECK约束、并发注册、80请求争抢20件库存、幂等、终态竞争、外层事务回滚、租户隔离、HTTP发布和库存流程、开发种子幂等。
- JwtValidationTest：1测试，通过真实RSA签名与本地JWKS验证合法令牌，以及错签、错issuer、错audience、过期、缺少exp的拒绝。
- QualityGateTest：2测试，验证生产类／公共方法中文注释、领域源代码导入边界。源代码导入检查不是ArchUnit完整字节码边界检查的替代。
- OpenApiTest：1测试，验证11个HTTP操作与10个路径的机器合同一致，无未来接口混入。
- 合计18测试，全部通过。原始报告在transaction-server/target/surefire-reports，运行日志在build-final.log；构建产物未提交，重新构建可复现。
- 使用非真实占位配置执行 `docker compose -f deploy/compose.yaml config --quiet` 成功；没有执行compose up或修改共享Docker组件。
- `bash -n scripts/verify.sh`、文档链接、OpenAPI内部引用与文件尾部空白检查通过。

## 已修复的验证问题

- 测试JDBC URL参数起始符错误，已修复；不影响默认应用URL。
- MySQL3819 CHECK错误被驱动映射为UncategorizedSQLException，断言改为验证确切数据库错误码及余额未变，没有绕过约束。
- JDK DocTree序列化会将中文转义；注释门禁改为读取原始注释，验证真正汉字。
- 价格／版本请求字段改为必填包装类型，避免缺失被反序列化为0；补充HTTP回归。
- JWT明确要求exp，避免只验证存在的时间声明而接受无期限令牌。

## 环境与未执行项

Java21.0.11、Maven3.9.12、SpringBoot4.0.2、Testcontainers2.0.3、mysql:8.4。测试仅创建临时容器及独立transaction_center_dev库，测试后自动回收；未触碰dev_infra共享数据。

未执行生产部署、真实认证平台联调、渠道支付、Kafka事件联调、大流量压测、容灾、安全漏洞扫描或完整生产设计验收。开发种子在隔离数据库中验证，并未往共享数据库擅自写入演示数据。

## 后续授权执行：本机共享MySQL建表

用户明确要求执行数据库表后，于2026-09-07在dev-infra-mysql84-1（8.4.11，本机43306）新建transaction_center逻辑库。执行前确认该库不存在，未清空、覆盖或迁移其他项目数据。

- 使用应用已锁定的Flyway11.14.1执行V001__foundation.sql，11张业务表创建成功。
- 新增并执行V002__migration_history_comments.sql，为Flyway自动创建的技术表及其10个字段补齐中文注释，原字段类型、默认值及索引保持不变。
- Flyway历史001、002均success=1；validate成功；全库12张表均有中文表注释，information_schema检查缺少字段注释数量为0。
- 未导入演示会员、商品或库存；未创建或重置应用账号；管理凭据仅经环境传入独立迁移进程，不写入源码或日志。
- 执行工具scripts/MigrateDatabase.java只运行迁移与校验，不启动HTTP或认证集成。迁移后重新打包应用以包含V002；本次未重新执行此前18项全量测试，实际数据库迁移和注释校验已执行。
- 已观察到Flyway对MySQL8.4的版本验证范围提示，以及原有TINYINT(1)字段显示宽度弃用提示；本次执行成功，生产发布前仍需评估版本兼容性，不能把这些提示解释为已获得生产认证。

## 继续开发：会员生命周期与查询能力

2026-09-07执行 `mvn -q -l build-lifecycle-verify.log verify`，退出码0。FoundationTest从14扩展为24项，QualityGateTest2项、JwtValidationTest1项、OpenApiTest1项，共28项，0失败／0错误／0跳过。

新增验收：

- 同版本20并发会员资料更新只1个成功，其他返回VERSION_CONFLICT，只有1条对应成功审计。
- 冻结阻止修改资料和新增GRANT，仍允许查询和撤回；解冻恢复编辑，历史everPaid／首单引用保持不变。
- 跨租户冻结和读取拒绝或空结果，非管理scope不能调用新管理接口。
- 未发布条款拒绝，同版本条款不允许覆盖；旧版本撤回不能影响更新版本的同意。
- 撤回后重放原GRANT只返回原资源，不重新赋予同意；历史序列按memberVersion单调递增。
- 外层事务失败时资料、版本、命令、审计和Outbox一并回滚。
- 仓库与库存流水有界分页、租户隔离；商品详情对会员隐藏下架商品。
- 数据库注释门禁范围扩展为整个测试库，包含Flyway历史表；Java中文注释门禁和22个API操作映射校验通过。

测试通过后，使用同一迁移工具在dev_infra交易专属库执行V003。执行前确认会员状态均满足新约束；001、002保持原脚本和校验和不变，003成功并通过Flyway validate。全库15张表／155个字段，缺失表注释和字段注释均为0。测试临时容器已回收，未向共享库导入测试数据或修改其他项目。

本轮未进行生产压测、真实身份平台联调或跨系统改造；只验证本阶段明确的业务与一致性边界。


## 退款审批拒绝审计（2026-09-07）

退款审批拒绝审计已修复、独立审查关闭P2并全量248项通过（0失败/错误/跳过），证据approval-rejection。尚缺异常多付补偿应用、退货唯一回库、正式V1资格/对账、真实渠道及流程、KMS、生产三高与完整裂变。
命令：`mvn -q -l build-approval-rejection-final.log verify`，退出0。15份Surefire报告与独立审查见`.codex/tasks/referral-end-to-end/evidence/approval-rejection/`。本切片无DDL或部署。


## 异常收款唯一补偿（2026-09-07）

退款拒绝审计与异常收款唯一补偿已实现，自动任务/六通道/补漏及故障回滚通过全量256项（0失败/错误/跳过），独立审查关闭间隙锁P2。共享V019/19迁移validate通过，36表498字段注释缺失0。仍缺真实履约退货唯一回库、正式V1资格/对账、真实渠道/流程/KMS、生产三高和完整裂变。
命令：`mvn -q -l build-compensation-final.log verify`，退出0；原始报告与审查见evidence/compensation。Compose静态校验通过，未部署。


## 实际交付与商业完成前置（2026-09-07）

拒绝审计、异常收款唯一补偿/原子任务/有界补漏及实际交付/独立商业完成前置均已实现并独立审查，最新全量260项通过（0失败/错误/跳过）。共享V020，20迁移validate成功，37表514字段注释缺失0。仍缺退货验收/唯一跨仓回库、正式V1资格/对账、真实渠道/流程/WMS/KMS和生产三高、完整裂变。
命令：`mvn -q -l build-fulfillment-final.log verify`，退出0；15份原始报告和独立审查见evidence/fulfillment。24Mapper/182SQL、9模块，45HTTP/35路径未新增；未部署，履约事实0行。
