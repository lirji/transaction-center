# 权威映射失败关闭查询切片

2026-09-08。依据 `IDENTITY_SCOPE_MAPPING_DESIGN.md` 第8条允许先实现Port/类型/失败关闭adapter，以及本轮用户继续权威映射并行推进授权。本切片不依赖待确认来源，未开启READY写入或生产入口。

## 交付行为

`AuthoritativeSubjectMappingPort` 是交易内部只读SPI。查询绑定认证上下文传入的tenant、精确canonicalSubject和观察时点；实现者负责验证权威来源，不接受业务body自报来源。默认Spring Bean `UnavailableSubjectMapping` 始终返回Pending，本地无会员/订单行不解释为权威不存在。

`AuthoritativeReferralMappingAdapter` 提供独立的customer与firstOrder就绪检查，未改变 `EvidenceController` 的memberId/orderIds/原生首单HTTP合同。返回只有内部state/reason，没有资格布尔值、外部evidenceId、customerRevision或结算字段；类型故意没有READY。请求方仍必须在HTTP或内部调用入口完成原有租户/actor授权，本服务不新增绕过认证的公开路径。

| 来源结果 | C02就绪检查 | C03就绪检查 |
| --- | --- | --- |
| 未配置/无法确认，Pending；来源抛RuntimeException | PENDING | PENDING |
| 查询绑定不符或空结果 | PENDING/MAPPING_INCONSISTENT | 同左 |
| effectiveAt晚于观察时点 | PENDING/MAPPING_NOT_EFFECTIVE | 同左 |
| 明确权威NotFound | NOT_FOUND；不授予新客 | PENDING；主体不存在不能证明期间订单不存在 |
| 具有旧/新主体、合并修订/生效时间的Merged | MERGED；不改查新主体 | PENDING；不合并消费与限领历史 |
| Mapped本地会员 | PENDING/CUSTOMER_POLICY_UNCONFIRMED | PENDING/ORDER_POLICY_SCOPE_UNCONFIRMED |

C02查询把请求asOf绑定到映射读取，禁止用晚于该时点生效的映射返回历史NOT_FOUND。C03就绪检查按当前观察水位读取主体状态；期间订单事实、映射历史和订单同快照还未接通，因此不会输出READY或订单NOT_FOUND。

输入policyCode/version保持外部形状，未定义已签收白名单，不将任意名称回退到原生策略。策略名非空只通过结构校验，不表示已支持；任何Mapped结果仍PENDING。没有调用原生资格/订单服务，故会员version=0、factVersion、FIRST_CONFIRMED_PAID_ORDER_V1、paidAt不能被隐式升级为C02/C03权威合同。未来具体策略签收后须补明确拒绝不支持策略的适配，而非沿用默认映射。

## 文件与验证

- `transaction-core/.../AuthoritativeSubjectMappingPort.java`：请求、不可变来源凭证、Pending/Mapped/NotFound/Merged强类型。
- `transaction-server/.../UnavailableSubjectMapping.java`：缺省失败关闭实现。
- `transaction-server/.../AuthoritativeReferralMappingAdapter.java`：内部只读就绪检查。
- `transaction-server/src/test/.../AuthoritativeReferralMappingAdapterTest.java`：10项纯测试，覆盖默认关闭、映射不等同READY、权威缺失/合并差异、租户/主体/水位隔离、精确字符串、历史生效时间、输入拒绝、凭证/合并不变量及来源敏感异常净化。

执行：`mvn --batch-mode -pl transaction-server -am -Dtest=AuthoritativeReferralMappingAdapterTest,QualityGateTest,MapperContractTest -Dsurefire.failIfNoSpecifiedTests=false test`。首轮9项功能测试通过，QualityGate发现新增compact构造器/接口方法缺中文说明，已补全；复验13项全部通过、退出0，日志 `build-authoritative-mapping-retest.log`，原始失败保留 `build-authoritative-mapping-focused.log`。没有FoundationTest、数据库访问、DDL、数据删除或全量回归。

主任务独立静态审查发现来源异常直接传播可能泄漏主体或内部地址，已改为捕获Port调用的RuntimeException并返回PENDING/AUTHORITY_UNAVAILABLE，不携带异常/cause、不记录来源文本。补含敏感文本异常的C02/C03断言后，只复验 `AuthoritativeReferralMappingAdapterTest`，10项全部通过、退出0，日志 `build-authoritative-mapping-review-retest.log`；未重复QualityGate、Mapper或全量。

## 剩余门禁

待确认来源认证、撤销/合并处理协议、追加历史存储与隐私保留期限、组织/店铺权威范围、策略名称与版本、customerRevision正数语义、settledAt/金额/首单标准、映射与交易快照一致性、C02/C03显式版本HTTP路径及双方签收。SPI本身不实施认证，不能把普通admin或用户JWT接到Mapped构造路径；受信接收器未完成前默认Bean不可替换为生产READY实现。

本切片只能证明未配置来源时安全不可用，不能据此宣称隐私方案、权威映射全链路、完整裂变或生产验收完成。独立审查由主任务完成静态检查，报告来源异常问题已修；最终复审结论由主任务统一记录。

## Unicode边界补强

权威主体及合并目标限制最多256 Unicode code points；允许合法补充平面字符，拒绝孤立代理字符，不trim或改大小写。新增边界测试后映射11项及Quality3项通过（build-authoritative-mapping-unicode.log，退出0）。独立复核无待修项；这些结果是映射切片专项，不属于先前归因428项全量。
