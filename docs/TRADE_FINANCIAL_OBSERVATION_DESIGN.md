# 交易资金积压观测

原范围：03-FINAL_PLAN 第180行和02-EXTERNAL_CONTRACTS_AND_API第12行。复用现有后台采样模式，不增加业务HTTP或改变资金状态。

- 支付域提供只读Port：UNKNOWN尝试数量下界及首次submitted_at年龄；HELD/SUBMITTED退款资金预占分别计数与created_at年龄；支付回调PENDING/REVIEW数量下界及received_at年龄。
- 退款域提供回调PENDING/REVIEW数量下界与received_at年龄；对账域提供OPEN/PROCESSING差异数量下界与created_at年龄。跨域不调用Mapper。
- 每固定状态最多扫描10001个覆盖索引项，SQL在所属域Mapper XML；V030只补状态、时间、ID覆盖索引，不修改V001～V029，不新增业务表。指标不输出租户、订单、渠道主体、金额或个人标识，不混合币种求和。
- UNKNOWN年龄明确为首次派发至今，不使用updated_at，避免重试掩盖长期未决。未派发PREPARED不计UNKNOWN。
- 单个worker周期线程采样，每15秒一次；每域短只读事务，失败保留上一样本并累加失败数；提供各域observed_at新鲜度，首次失败返回NaN，不能伪报零积压。Prometheus抓取不查库。
- 聚合方法仅内部固定状态、无参数、无业务数据结果，是租户SQL规则的窄例外；门禁固定分支数、LIMIT及覆盖索引。生产告警阈值及压测仍需真实环境验证。
- 验证集中覆盖：各状态与终态排除、最老时间、空集合、10001行上限/EXPLAIN覆盖索引、观测失败不清零及恢复。与当前缓存阶段分开跑关联测试，阶段结束一次全量。

traceparent单独跟进，先核对现有事件信封和外部合同，不把本地correlationId伪称跨服务追踪。

独立设计复核：UNKNOWN是首次派发许可后的未决年龄，不证明网络已发送；退款预占年龄包含审批等待。全局覆盖索引必须以status（预占为state）、对应时间、id开头，不能复用tenant前缀索引声称全局有界。各域独立observed_at，局部失败不被健康域时间覆盖。
