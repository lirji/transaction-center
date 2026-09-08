# 跨服务追踪上下文接入

原合同02第12行要求traceparent跨服务透传，敏感字段不进日志。当前事件traceId是持久UUID；审批拒绝Failure.traceId是ARJ审计编号，必须保留定位能力。规范依据为[W3C Trace Context](https://www.w3.org/TR/trace-context/)，不是自行扩展身份认证协议。

- core提供有界不可变TraceContext及显式可关闭线程作用域；非InheritableThreadLocal，嵌套作用域恢复旧值，线程复用不能遗留前请求。trace-id为32位小写十六进制、span-id为16位，均非全零。新的本地ID使用随机值。UUID事件traceId保留原字段和格式，用同128位转换关联，不改历史载荷或schemaVersion。
- HTTP最外层过滤器校验单个traceparent：00版恰好55字符；ff/坏字段/全零/多值忽略并创建新根；较新版本在有界长度内按已知前缀兼容解析。进入服务生成新span，保持有效trace-id。响应返回当前traceparent。它仅为观测关联，不能影响认证、租户或业务授权。不透传任意tracestate/baggage，不记录原始头，不增加逐请求日志。
- 错误响应的普通UUID关联取当前trace；ARJ审批审计编号保持不变，独立响应traceparent连接该请求，不能覆盖审计回执或把trace-id当审计主键。
- V031在tc_job_lease增加可空trace_parent（55字符、中文注释及规范格式约束）。新insert/schedule在原事务捕获当前上下文，ON DUPLICATE不改原值。已有任务NULL保留，领取/续租返回该上下文；恢复和重试不改它。wake/人工重放沿原因果根，不更改永久业务/事件身份。
- 八个Worker的processLease使用显式作用域包住原实现；按持久父上下文创建当前span，无上下文的历史任务新建本地根，finally恢复线程上下文。通过真实持久任务与复用线程测试，不能只验证静态解析器。
- V1事件工厂优先使用当前trace对应UUID，无上下文仍生成本地UUID。OutboxEventCatalog验证后向Message携带原traceId；Kafka附加符合W3C的traceparent，保持原eventId头和payload逐字不变。当前Worker trace与事件相同时沿当前span；历史补漏根不同时使用事件trace-id生成独立发送span，不伪造未知父span。历史全零UUID无法作为W3C trace-id时新建安全本地根，不改原载荷。
- 当前没有真实渠道HTTP适配器，不声称已传播到支付供应商；提供上下文读取给未来适配器。WMS/流程/营销消费者及实际collector/exporter接入、生产采样与跨平台验收保留未完成。该阶段交付的是上下文传播，不声称已具备可视化追踪平台。

验证集中覆盖：无头/合法/坏值/多值/新版本前缀、跨租户线程复用隔离、HTTP到V1及耐久job一致、重试/接管与历史NULL、Kafka原载荷与新增头、ARJ审计号保留。资金状态、永久重放、原锁序和外部网络事务边界均不得变化。

独立设计复核补充：兼容解析较新版本后，输出与持久化统一为本服务支持的00版55字符；仅继承sampled位，新根默认00。上下文仅包processLease，不包claim/维护轮询。人工重放不能替换持久事件trace。Kafka新增traceparent头须同步生成器与AsyncAPI，不增加或修改原payload字段。
