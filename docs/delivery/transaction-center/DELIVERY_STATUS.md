## 订单归因阶段完成（2026-09-08）

归因令牌、永久快照、单次消费及Commands淘汰后原成功回放已接入，默认关闭。真实RS256/JWKS、外层事务拒绝、商户锁/地址加密后时间复检、租户/主体隔离、无token兼容、原子回滚及取消不释放均有证据。

验证：组合 `mvn -q -l build-attribution-full.log clean verify` 退出0，428项/34套，失败/错误/跳过均0，包含归因、SDK及自动准入。随后仅收紧3项测试断言，定向补验3项退出0；不把它们相加成431项全量。独立审查关闭同报价掩盖令牌竞争的测试缺口，实际使用两份报价竞争同issuer/jti。证据位于 `.codex/tasks/referral-end-to-end/evidence/order-attribution/`。

共享dev_infra V032迁移及32版validate通过，48张业务表678字段，表/字段中文注释缺失0，归因表0业务行。V001～V032冻结，下一未占用号V033。无应用部署、真实业务开关启用、提交或推送。GitHub Actions新增构建/隔离测试及报告保存，YAML/bash语法和底层本地命令验证；远程CI/安全扫描未执行。

后续并行mapping_adapter交易内部失败关闭映射适配、referral_runtime原营销平台纯规则SPI，属于新切片，**不在上述428全量覆盖内**。真实映射来源已向用户询问，隐私提案、真实渠道和生产参数保持待确认；保留161项及完整裂变/生产门禁。

## 2026-09-08 当前恢复核对（覆盖下方旧交接状态）

用户已要求继续按 ORDER_REFERRAL_ATTRIBUTION_DESIGN 实施，无需重复设计审批。现场发现中断前已存在归因生产代码、V032、SDK/错误码/OpenAPI接线和4项真实RSA测试，旧“无代码/V032”描述已过时。共享迁移尚未执行，本次不改V001～V031。

已完成：读取原目标/契约/manifest/PROGRESS，确认161项保留；真实RSA及静态门禁专项退出0（build-attribution-unit.log），合同80码/7事件/12正例/9检查通过；独立只读审查未发现确定阻断，要求补集成证据。

正在补充 FoundationTest 的永久回放、去token/改地址、原子回滚、取消不释放、外层事务拒绝、加密后过期、并发同请求、真实RSA入库/跨主体、锁后过期及竞争键场景。首轮5项数据库专项及追加4项均已退出0，证据evidence/order-attribution/first-focused和edge-focused。组合全量20970运行，日志build-attribution-full.log；独立复核后修改了竞争测试为两个quote并收紧两个时间断言，全量编译后变更，须在其结束后专项补验3项。尚未获得本阶段全量结果，未迁移共享库、提交或部署。

下一步：完成数据库专项并修复问题，补旧RR竞争回归，经复审后一次组合全量并共享V032迁移；继续隐私（未批准方案不得实施）、权威映射、原平台完整裂变。当前403项全量仍仅代表旧追踪阶段，生产门禁保留。

# Delivery Status

当前检查点：隔离队列/永久受控重放阶段全量37604退出0，326项、0失败错误跳过，evidence/outbox-replay已封存；独立审查无剩余阻断。共享V023迁移64110退出0，40业务表563字段注释缺失0，Outbox/重放凭证0行；52HTTP/42路径，无部署或真实业务发布。下一步历史维护/告警、对账与完整裂变；后续源码验证状态以PROGRESS为准。下方旧数字仅为历史阶段证据。


## 当前验证基线（覆盖下方历史阶段）

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

## V015退款Inbox（当前全量验证中）

V015退款持久Inbox、核心证据保护Port/装配层复用、租约回调Worker及第四独立通道已实现。13项Foundation退款Inbox测试；初轮4425运行时测试缺新Worker替身及Mockito事务代理设桩错误，二轮13220 MySQL异常包装断言和嵌套Mockito时钟桩错误，均修复并保留失败报告。当前exec22616/build-refund-inbox-final.log全量运行，尚无最终结论；共享仍V014。新增22Mapper/170SQL，HTTP不变43操作/33路径。后续REFUND_RECOVER、真实供应商及完整裂变仍缺。

独立Agent refund_inbox_review只读复核事务/锁序/事件唯一性/AAD/可靠失败及DDL NULL约束，未发现阻断缺陷；未代跑测试。新增真实MySQL用例覆盖陈旧REVIEW回滚、伪造业务引用、字段重算摘要仍不能绕过认证。十秒网络预算/真实验签/KMS/生产容量仍非已完成能力。

## 退款渠道边界最终验证（2026-09-07）

退款渠道边界已验证：全量mvn -q -l build-refund-channel-final.log verify（exec58350）退出0，193项（Foundation152＋RefundChannel10＋其他31）全部通过。之后商户号限制128→255并新增兼容测试，最终源码专项mvn -q -l build-refund-channel-compatibility.log '-Dtest=RefundChannelContractTest,QualityGateTest,MapperContractTest' -Dsurefire.failIfNoSpecifiedTests=false test（exec55520）退出0，15项（11＋3＋1）全部通过。不是最终源码单次194项全量；最终修复已按影响面专项复验。原始证据位于.codex/tasks/referral-end-to-end/evidence/refund-channel/{full,final-focused}。无新DDL/HTTP，共享V014，21Mapper/164SQL及43HTTP/33路径不变。真实Adapter/Inbox/Worker、强制网络超时与安全同号重放仍待实现。

以下为历史阶段记录，旧的运行中状态已终结。

## 退款渠道边界（2026-09-07，当前回归中）

新增RefundChannelPort/Adapter/Service、RefundEvidencePort和VerifiedRefundEvidence。无接收器禁止发网；仅CREATE/QUERY、原路同号、事务外调用；UNKNOWN/PENDING不改资金；同号异常证据保留、接收异常不确认。无真实Adapter/Inbox实现、无HTTP或DDL新增，下一步V015耐久接收及Worker，见REFUND_CHANNEL_RECOVERY.md。

首批RefundChannelContractTest 10项＋Mapper/Quality4项退出0（exec4620）。全量exec58350/build-refund-channel-final.log运行中；编译后补商户号128→255兼容修复和第11项测试，完成后必须跑最终源码专项，不能把旧报告当新代码已通过。独立Agent refund_channel_review只读审查无阻断发现、未运行测试。已同步TC20/21/23/25/29的V014证据。共享仍V014。

## V014最终验证（2026-09-07，当前状态）

V014正常退款申请已通过本地全量回归：`mvn -q -l build-refund-application-final.log verify`（exec45973）退出0，183项测试（Foundation152＋其余31），0失败/错误/跳过，其中新增19项退款申请测试。共享dev_infra已执行V014（exec59200退出0），14个迁移validate成功；35表/469字段中文注释缺失0，四张新增业务表均0行。21Mapper/164SQL、43HTTP操作/33路径；报告在`.codex/tasks/referral-end-to-end/evidence/refund-application/`。V001～V014冻结，后续增量V015。无应用部署、业务种子写入或其他平台改造。实际退款渠道、耐久Inbox/Worker、可信流程审批、退货验收、正式资格证据及生产三高验收仍未完成。

以下保留阶段历史记录；其中“正在验证”“V014未执行”仅描述当时状态。

## V014正常退款申请（当前验证中）

transaction-refund、OrderRefundPort/Service/Mapper、PaymentRefundPort.lockNormalSource、V014四表及4个HTTP已实现；21Mapper/164SQL、43操作/33路径。申请/双额度/永久请求、持久策略、内部审批/派发/成功/可靠失败/到期与订单财务版本同事务，实际渠道与退款InboxWorker仍缺。

基础编译96279退出0；39289首批18项中1个重复SKU夹具错误，97059扩展24项中1个审计表名断言错误，均已修复并归档。新增19项退款申请集成，全量build-refund-application-final.log / exec45973正在运行；本阶段不能用此前164项代替当前验收。V014未共享执行，共享仍V013。下一步全量结果、最终文档/迁移/检查点，然后实际退款恢复与耐久证据、审批认证/后台等剩余内容。以下是历史已验证切片。

## V013退款资金前置（已验证）

PaymentRefundPort/Service、RefundSource/Hold、PaymentRefundMapper/XML、V013及14项Foundation测试已完成本地验证。9项首批退款测试加Mapper/Quality4项通过；最终build-refund-funds-final.log / exec67271退出0，164项全通过（Foundation133＋其他31），0失败/错误/跳过。10份原始报告在evidence/refund-funds。

独立审查后修复成功时间重放冲突并更新账本中文注释。补充旧租约回滚、成功/失败竞争、混合部分退款、跨订单渠道号冲突和异常多付退款。新增REFUND_FUNDS_CONTRACT.md并同步实施计划、README与持久化设计；19Mapper/142SQL，HTTP仍39操作/30路径。共享V013迁移/validate已通过（exec59468退出0），31表/415字段中文注释齐全，hold/收款/账本0行；无部署或共享测试业务写入。下一V014，接续退款应用/明细/审批/原路恢复；完整目标仍in-progress。以下为历史切片。

## V012支付风控门禁（2026-09-07，当前切片）

已实现持久风险历史与精确当前指针、默认WAIT策略、首次及同号补建CREATE有效ALLOW门禁、风险网络事务外、结果与fencing同事务、等待租约锁后的许可终检。ALLOW/DENY后100ms持久重检，WAIT才指数退避；QUERY/CLOSE及真实资金处理不被风险阻断。接口与状态/外部缺口见docs/PAYMENT_RISK_GATE_PLAN.md（相对本文件定位docs根目录）。

最终`mvn -q -l build-payment-risk-final.log verify`退出0，150项全通过（Foundation119＋Worker10＋RiskProvider4＋其他17），0失败/错误/跳过。10份原始报告和共享迁移输出在evidence/payment-risk。新增10项集成/4项Provider合同，包含12并发、SQL/FK约束、旧轮/旧租约、风险网络中取消/接管、真实行锁等待并推进业务Clock后拒绝过期CREATE。首次定向3个夹具错误已修复，失败报告保留；18项定向通过后全量150项再通过。

共享V012 migrate/validate退出0，001～012全成功，30表/394字段中文注释齐全，风险/支付尝试/任务0行；无共享测试业务写入、其他项目修改或应用部署。V001～V012冻结，下一V013；18Mapper/130SQL、39HTTP操作/30路径。内部范围pass，全生产仍conditional-pass：真实风险/渠道供应商、网络时限/配额、KMS、REVIEW运营/采集报警、退款/履约/正式消息/CI/三高容灾与完整裂变尚未完成。下一步按REFUND_IMPLEMENTATION_PLAN.md继续，并保留全部前序缺口。以下为历史记录。


## 当前进行中：支付风控门禁

PaymentRisk持久历史/尝试指针/Mapper XML/V012、默认WAIT策略、首次及同号补建CREATE许可、风险网络事务外及结果fencing已实现，待完整验证。独立审查确认并修复ALLOW被长退避错过和SQL空截止时间CHECK缺口；指针避免空范围查询，fencing后终检到期许可。

首次定向12项有3个测试夹具错误，已修复并归档；build-risk-focused-fixed.log / exec36620已退出0，18项全通过（10集成/4Provider/Mapper1/Quality3）。当前build-payment-risk-final.log / exec43293全量verify运行。前序支付夹具显式准备测试ALLOW，不修改生产默认WAIT。共享V011未变，V012未执行，最终全量回归/文档/迁移尚待完成。以下为历史已验证切片。

## V011常驻Worker（2026-09-07）

默认关闭的WorkerRuntime、三类固定容量通道、索引租户轮转、整轮工作计数空闲策略、有界停机和低基数进程指标已实现。最终`mvn -q -l build-worker-runtime-release.log verify`退出0，136项全通过（Foundation109＋WorkerRuntime10＋其他17），0失败/错误/跳过。9份原始报告及迁移输出见`.codex/tasks/referral-end-to-end/evidence/worker-runtime/`。

共享V011迁移和validate成功，001～011全成功；29表/374字段中文注释完整，订单/任务0行。只增加带中文注释的调度索引，未写测试业务数据或部署应用。Compose显式worker profile的配置校验通过，普通API固定禁用后台。当前17Mapper/126SQL，HTTP仍39操作/30路径。

新增3项真实MySQL测试覆盖内部发现、终态排除/未来任务、EXPLAIN range索引无filesort及运行时与真实租约联动；10项运行时测试覆盖开关、容量、隔离、轮转、两种空闲吞吐回归、异常恢复和停机。测试Batch不代表真实支付渠道、多实例长稳或三高验收。首次单元编译的submit重载歧义已修复；独立审查确认并修复两次空闲判定P1，中间135项通过后重新136项全量验证。

本切片内部验证pass，全生产仍conditional-pass：队列年龄/采集报警、实际网络超时/风险准入/真实渠道/KMS/退款/履约/正式消息/CI/容量与容灾/外部签署仍缺；全裂变目标未完成。下一步见`docs/PAYMENT_RISK_GATE_PLAN.md`，并保留Worker合同的全部运维缺口。以下为历史记录。


## 当前切片：V010订单解析与唤醒已验证

ORDER_RESOLVE创建预约／取消和收款唤醒、RUNNING代数防丢唤醒、未派发安全关闭、订单Worker与有界全订单分页补漏已实现。build-order-resolution-final.log退出0，123项全通过；evidence/order-resolution保留原始8份报告及迁移输出。

V010共享迁移validate成功，29表／374字段中文注释齐全，订单／任务0行，下一V011；17Mapper／125SQL、HTTP39操作不变。无其他项目修改、应用部署或真实收款。

总体in-progress。下一步按docs/WORKER_RUNTIME_PLAN.md补常驻租户公平调度、执行隔离和停机；然后风控／真实渠道／退款／履约／正式消息／CI及全量生产验收，交易内部完成后完整裂变改造。以下为历史状态。

## 当前切片：支付恢复编排已通过本地验证

新增PaymentRecoveryWorker、支付恢复与渠道Port、固定渠道策略注册、未知结果退避、可信证据耐久交接及认证能力下同号补建。当前109项测试全通过，build-recovery-release.log退出0；证据见evidence/payment-recovery及docs/PAYMENT_RECOVERY_WORKER.md。数据库仍V009，本轮无迁移、共享业务写入或部署。

整体状态仍in-progress。下一切片按docs/ORDER_RESOLUTION_PLAN.md补订单级取消／过期任务和持久唤醒；之后常驻调度、风控、真实渠道、退款／履约／正式消息／CI及全生产验收，交易完成后完整裂变改造。以下历史状态以本节为准。

## 2026-09-07：V009 支付回调内部切片（当前状态）

已实现可信适配器之后的密文耐久接收、事件去重、回调任务同事务入队及带租约的本地处理。SQL 使用 CallbackMapper/XML；跨模块使用 PaymentCallbackPort。当前 8 模块、17 Mapper／121 SQL，HTTP 仍为 39 操作／30 路径。

验证命令 `mvn -q -l build-callback-release.log verify` 已退出 0：95 项通过（Foundation83＋其他12），失败／错误／跳过均为 0。原始报告及失败历史在 `.codex/tasks/referral-end-to-end/evidence/callback-inbox/`。覆盖并发去重、接收回滚、旧租约业务回滚、错误业务引用拒绝、密文及归属篡改、密钥轮换／暂缺重试、事务开启失败、真实提交后模拟确认丢失。内部故障注入不等于真实渠道或 HTTP 断响应验收。

曾真实复现连接池耗尽：NEVER 空同步上下文中 Mapper 预读占连接，后续自有事务再取连接。已改为独立短读事务释放后再加密／写事务；保留 10 连接池及 12 线程并发，新增加密阶段无数据源连接绑定的确定性断言。另修复测试中对 MANDATORY 代理直接配置 Mockito 导致的夹具异常，业务事务要求未放宽。独立只读审查已复核关键设计；审查本身没有执行测试。

共享 dev_infra 的 transaction_center 已执行 V009 并 validate 成功，001～009 全成功。含 Flyway 历史表共 29 表／372 字段（业务表 28／362），所有表及字段中文注释缺失均 0；回调、支付尝试、任务均 0 行。没有写入共享测试业务数据或部署应用。V001～V009 冻结，下一迁移 V010；Flyway 对 MySQL 版本验证范围警告仍保留。

范围仍未完成：真实验签／渠道调用／HTTP 应答合同、常驻调度与取消过期唤醒、冲突告警、退款／履约、正式消息、KMS、CI、三高和容灾验收，以及交易完成后的完整裂变改造。详见 [回调合同](../../PAYMENT_CALLBACK_INBOX.md)。以下较早章节为历史切片，不代表当前待实现项。

## Goal

完成完整交易中心生产设计及逐项可验证验收，不以局部功能或已有28测试替代全范围完成。

## State

- Phase: 实施；支付核心与V008任务租约已验证，耐久回调／Worker编排／真实渠道／退款待继续
- Status: in-progress
- Last updated: 2026-09-07

## Completed

- 最新任务切片：transaction-jobs、JobSchedulerPort／JobType、支付同事务入队、租约／接管／续租／fencing事务提交及升级分页补漏。8模块／16Mapper／116SQL，39HTTP操作不变；build-job-final.log退出0，79项全通过。
- V008共享迁移与validate成功：28表／347字段注释完整，任务／尝试均0行；下一迁移V009。原始证据evidence/job-leases/，新合同docs/JOB_LEASES.md。

- 支付核心：7模块，15Mapper／108SQL，39HTTP操作／30路径；可信收款与订单／库存／首单／账本同事务、可靠关闭处理、异常资金保留。见PAYMENT_CORE.md及69项release回归报告。
- V007共享迁移及validate成功；27表／332字段注释完整，新增表0业务数据。下一迁移V008，不修改V001～V007。

- 恢复并核对现有代码、进度、原TC-01～32及INFRA-01～06门禁。
- 确認GitHub远程，CI平台无需重新选择。
- 已有基础／会员生命周期实现仅为部分证据，尚非完整AC达成。
- 用户新增Mapper要求已落地：9个Mapper＋XML、60条SQL、12个model，Service无JDBC及SQL；现有业务与HTTP合同保留。
- 多SKUHTTP及不可变报价已完成，含固定价策略、QuotePort、QuoteMapper、同事务审计与可控制时钟；AC-02报价子项有证据，订单相关TC仍待实现。
- 新增transaction-order：逻辑商户、地址AES-GCM、订单创建／查询／取消、单报价唯一订单、多SKU原子库存、零元订单、本地支付许可屏障。订单部分TC已具备证据，真实渠道及完整链路仍待完成。
- 新增MemberTradeFactPort／Service、Mapper＋XML及V006：会员事实行锁、订单级永久去重、唯一首单槽位、不可逆累计、冻结仍接收事实、同事务内部Outbox。没有新增HTTP支付入口。
- 按long-task-continuity技能建立.codex/tasks/referral-end-to-end原目标及原设计全文快照、跨阶段约束和需求映射，不替代既有原文或缩减生产门禁。

## Changed Files

- docs/delivery/transaction-center/DELIVERY_PLAN.md：恢复完整范围与门禁。
- 本状态文件：连续交付追踪。
- 各模块mapper／model／resources/mapper、服务注入、类型转换、MyBatis配置、Mapper合同与回归测试、docs/PERSISTENCE_DESIGN.md及恢复文档。

## Verification Log

| 检查 | 结果 | 备注 |
|---|---|---|
| 代码／进度核对 | 当前V001～V003，5模块 | 尚无报价／订单／支付模块 |
| git remote -v | GitHub lirji/transaction-center | 未推送 |
| 上轮28项测试 | 历史证据 | 后续修改必须重跑 |
| mvn -q -l build-mapper-final.log verify | 34项全部成功，退出0 | 0失败／0错误／0跳过，仍非全范围生产验收 |
| SQL抽离前后规范化比较 | 60条一致 | 占位及结果别名变化，不改变条件或锁 |
| V004迁移 | 隔离MySQL成功 | 共享交易库尚未执行，本次无共享库写入 |
| mvn -q -l build-quote-final.log verify | 40项全部通过，退出0 | 28个HTTP操作／22个路径，订单TC仍未完成 |
| V004共享迁移及Flyway validate | 成功，版本004 | 17张表／183字段注释完整，无测试数据写入 |
| mvn -q -l build-order-release-verify.log verify | 50项成功，退出0 | 35个HTTP操作／27个路径；12个Mapper／78个SQL |
| V005共享迁移及Flyway validate | 成功，版本005 | 21表／244字段注释完整，订单及商户均0业务行 |
| Compose config --quiet | 退出0 | 仅校验配置，未部署应用 |
| mvn -q -l build-member-facts.log verify | 55项全部通过，退出0 | V006隔离迁移成功；13个Mapper／83SQL；HTTP保持35操作 |
| V006共享迁移及Flyway validate | 成功，版本006 | 22表／259字段注释完整，消费认定0业务行 |
| mvn -q -l build-member-facts-release.log verify | 57项全部通过，退出0 | 独立审查补强后重跑；MySQL3819断言分类修复，不更改业务 |

## Decisions And Deviations

保持已批准架构并按用户补充采用Mapper接口＋XML；不改已执行迁移，不修改现有其他项目。所有生产门禁保留。暂不增加无实际职责的空Repository转发层。

## Blockers And Residual Risks

真实渠道／生产资源等缺失，但当前仍有可独立实现工作，不处于停止状态。

## Next Action

从V009继续耐久原始观察接收、验签加密与渠道Port、接入租约的Worker编排、取消／过期唤醒、风控及有界重试；不重做已交付支付核心／任务租约。再完成退款／退货、履约、隐私／KMS、正式事件、CI和生产门禁后进入现有平台裂变改造。真实渠道未接通，不开放真实收款。完整目标不缩减。


## 退款审批拒绝审计（2026-09-07）

退款审批拒绝审计已修复、独立审查关闭P2并全量248项通过（0失败/错误/跳过），证据approval-rejection。尚缺异常多付补偿应用、退货唯一回库、正式V1资格/对账、真实渠道及流程、KMS、生产三高与完整裂变。
命令：`mvn -q -l build-approval-rejection-final.log verify`，退出0。15份Surefire报告与独立审查见`.codex/tasks/referral-end-to-end/evidence/approval-rejection/`。本切片无DDL或部署。


## 异常收款唯一补偿（2026-09-07）

退款拒绝审计与异常收款唯一补偿已实现，自动任务/六通道/补漏及故障回滚通过全量256项（0失败/错误/跳过），独立审查关闭间隙锁P2。共享V019/19迁移validate通过，36表498字段注释缺失0。仍缺真实履约退货唯一回库、正式V1资格/对账、真实渠道/流程/KMS、生产三高和完整裂变。
命令：`mvn -q -l build-compensation-final.log verify`，退出0；原始报告与审查见evidence/compensation。Compose静态校验通过，未部署。


## 实际交付与商业完成前置（2026-09-07）

拒绝审计、异常收款唯一补偿/原子任务/有界补漏及实际交付/独立商业完成前置均已实现并独立审查，最新全量260项通过（0失败/错误/跳过）。共享V020，20迁移validate成功，37表514字段注释缺失0。仍缺退货验收/唯一跨仓回库、正式V1资格/对账、真实渠道/流程/WMS/KMS和生产三高、完整裂变。
命令：`mvn -q -l build-fulfillment-final.log verify`，退出0；15份原始报告和独立审查见evidence/fulfillment。24Mapper/182SQL、9模块，45HTTP/35路径未新增；未部署，履约事实0行。
