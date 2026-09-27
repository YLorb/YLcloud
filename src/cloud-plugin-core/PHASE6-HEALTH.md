# Phase 6：健康检查与故障隔离

本页保留 Phase 6 阶段讲解；后续已按授权完成 [Phase 7 管理 API](PHASE7-API.md)。未启动真实 Docker 或 PaddleOCR。

## 1. 业务目标

在整包发布前执行宿主定义的能力样例；任一 Provider 或结果断言失败，整包不发布。
运行后提供显式健康复查和被动故障统计。一个插件连续后端故障达到阈值时，阻止本轮 Runtime 的后续引擎调用，保护其他插件的独立线程池。

本阶段是进程内调用隔离，不能隔离 JVM 崩溃、native 故障或强杀不响应中断的线程。

## 2. 核心抽象及职责

| 抽象/字段 | 职责 |
| --- | --- |
| PluginHealthPlan | 宿主提供的不可变整包样例计划，最多 256 个样例，总预算大于零且不超过 5 分钟 |
| Probe.request | 无敏感数据的固定测试文档与选项，经过 Runtime 的快照及限额处理 |
| Probe.accepts | 宿主提供的可信结果断言，验证实际输出，不由插件自报成功 |
| HealthSnapshot.runtimeId | 本轮运行的 UUID，避免把旧一轮检查结果归到重启后的实例 |
| HealthSnapshot.status | 健康状态，与 Manager 发布状态、Runtime 生命周期分别表达 |
| consecutiveFailures | 本轮连续计数的后端故障数，按调用结果处理顺序统计 |
| checkedAt | 最近一次完整健康检查结束时间；未检查为 null，不是实时存活证明 |
| isolated | 本轮终态隔离标记，禁用或迟到成功不能清除此标记 |
| healthBlocked | 复查失败后的业务入口阻断标记，成功复查才能清除 |

健康状态：UNCHECKED（低层未验证入口）、CHECKING、HEALTHY、UNHEALTHY、ISOLATED、INACTIVE。
INACTIVE 仅表示 Runtime 已停止接收调用，不表示线程和资源都已释放，仍需看 Runtime 生命周期。

健康计划必须为每个声明 Provider 提供至少一个样例。计划校验覆盖声明的 MIME、语言，并在声明支持表格/公式时至少有相应有效 true 选项。
这是各维度覆盖，不是 MIME × 语言 × 功能的笛卡尔积，也不是证明内容中确实包含表格或公式。
固定样例的实际内容与结果断言由宿主负责：例如断言 FORMULA 块及预期 LaTeX、表格块及关键单元格文本。
不能把永远返回 true 的断言当成有效业务验证。仓库本阶段使用测试替身验证框架规则，没有提供真实 PaddleOCR 样例基准。

## 3. 完整调用链

受管启用：
Manager.enable(id, mode, factory, limits, plan)
→ 启动前 plan.validate 核对整包覆盖
→ 创建并关联 Runtime，记住 Entry/revision
→ factory.start 创建 Session 与实际实例
→ Runtime.checkHealth：CHECKING
→ 对全部样例调用 Runtime.invoke(probe=true)
→ 有界快照、线程池执行实际 parse、结果断言、同一截止时间检查
→ 全部通过：HEALTHY
→ Manager 核对实际元数据、Entry/revision
→ Registry 整批发布受控包装对象。
任一步失败均走已有停止/排空/清理流程；未结束任务仍保留资源归属。

显式复查：
Manager.checkHealth → 保存 Entry、Runtime、revision
→ 锁外执行整包检查 → 返回前重新检查归属。
同一个 Runtime 同时只允许一个检查；CHECKING 暂停接收新业务调用，检查前已接收的任务可继续。
复查失败为 UNHEALTHY，拒绝后续业务任务；再次显式复查全部通过可以恢复。
没有后台轮询、自动重试或自动切换 Provider。

被动故障隔离：
包装 Provider.parse → 实际引擎调用 → 按结果分类
→ 连续计数达到 3 → ISOLATED
→ 旧引用的新调用和尚未进入引擎的排队任务均拒绝。
已进入引擎的任务不强杀；迟到成功不解除隔离。
恢复：disable → awaitStopped 成功 → 用健康计划重新 enable，创建新的 runtimeId。

## 4. 为什么采用当前设计

实际解析固定样例比只检查端口或进程存在更接近业务可用性。
计划由宿主提供，避免插件仅返回一个健康布尔值就宣称所有能力可用。
样例沿用 Runtime 的有界线程池、快照和截止时间，不另外创建无界的探测线程。

主动检查与被动计数互补：前者验证预期结果，后者发现上线后的引擎失败。
计数以整包 Runtime 为单位，与整包发布规则一致。同包任一 Provider 的后端故障都可能隔离整个包。
隔离不自动恢复，避免冷却后直接放行到尚未退出的故障引擎。

## 5. 至少两个替代设计

1. 只检测端口/容器存活：轻量，但不能发现模型缺失、无法解析、输出结构错误。使用业务样例与宿主断言。
2. 所有异常都计入隔离：实现简单，但用户上传坏文档或队列拥堵就可能隔离正常插件。区分输入/容量错误和实际引擎故障。
3. 冷却后自动半开恢复：可用性更自动，但引入探测名额、旧任务结果与新一轮探测竞争。本阶段选择显式重启并重新验证。
4. 每个 Provider 独立熔断：故障范围更小，但同包可能共享一个进程、模型和资源。本阶段采用包级故障边界。

## 6. 故障分类和工程风险

实际进入 Provider 后，UNAVAILABLE、INTERNAL_ERROR、DEADLINE_EXCEEDED、非预期运行异常计入后端故障。
队列等待超时但尚未进入引擎不计入；排队满、快照读取失败、请求无效、选项不支持、限制超出及调用方主动中断不计入。
引擎成功会清零非隔离状态的业务故障计数；健康检查的单个成功不清零，整包通过后才清零。
并发时按调用方处理结果的顺序计数，不是请求发起顺序；隔离一旦成立，本轮不再恢复。

- 健康样例的质量决定验证意义。覆盖检查只验证样例选项，实际断言必须验证功能输出；不保证所有文档正确、准确率或持续可用。
- plan.budget 是整包解析与断言前后的共同截止时间，但不能强制中断阻塞的宿主源读取或可信断言。断言必须快速、纯计算、无外部 I/O；Factory.start 和 Session.close 的预算仍由适配器负责。
- 探测使用业务线程池，拥堵可能使复查失败，导致 UNHEALTHY 并暂停调用。这是保守策略，调用方应择时检查；没有自动轮询。
- 隔离只关闭业务入口，不从 Registry 自动删除登记，不把 Manager 自动改为 DISABLED，不立即释放资源。候选列表仍可能含该 Provider，调用方需结合 runtimeHealth 展示状态；调用包装对象会快速失败。
- 固定阈值为 3，尚未做时间窗口、可配置阈值、自动恢复或持久化。
- 进程内线程池不能隔离 OOM/native 崩溃，也不能强杀引擎。真实容器/进程隔离需后端适配器；本阶段没有 Docker 实测。
- 无健康验证的 Phase 5 工厂入口明确改名 enableUnverified；旧 Map 入口仍是裸实例低层能力。Phase 7 应仅接入带 plan 的启用流程，不把低层接口暴露给终端用户。
- 原四参数工厂 enable 的 Java 调用方须迁移到带计划 enable 或显式 enableUnverified；现有 Phase 5 测试已迁移，SPI 不变。
- 错误消息固定，不输出文档、样例、断言或后端异常正文；cause 仍只供受控诊断。

## 7. 关键代码逐段解释

```java
plan.validate(expected.manifest);
runtime.start(factory);
runtime.checkHealth(plan);
return publish(...);
```

实际代码先在管理锁内做纯计划验证与关联，再锁外启动和检查，最后执行带 Entry/revision 核对的整批发布。
没有先注册一个 Provider 再测下一个 Provider 的中间可见状态。

```java
var context = new ProviderCallContext("health-" + UUID.randomUUID(), Instant.now().plus(plan.budget()));
```

整包只创建一个截止时间，不给每个样例重新发一份完整预算。

```java
var result = invoke(target, declaration, request, context, true);
if (!probe.accepts().test(result)) { ... }
```

probe=true 只允许检查穿过 CHECKING/UNHEALTHY 门禁，不绕过限流、快照、截止时间，也不能穿过 ISOLATED 或禁用。
返回非 null 不代表通过，必须满足宿主定义的断言。AssertionError 也转为安全的检查失败，不滞留 CHECKING。

```java
if (targetStarted.get()) { recordOutcome(true, probe); }
```

工作线程进入引擎前设置该标记。尚未进入引擎的超时不能归咎于模型，否则队列拥堵会错误触发隔离。

```java
if (++consecutiveFailures >= FAILURE_THRESHOLD) {
    health = Health.ISOLATED;
    isolated = true;
}
```

计数与标记在 gate 下更新。包装入口与排队任务进入引擎前也检查隔离标记。
isolated 标记不会因 stopAccepting 把展示状态改成 INACTIVE 而丢失，避免禁用时重新放行被隔离的排队任务。

```java
if (state != State.RUNNING || health == Health.ISOLATED) { return; }
```

已隔离或已停用时忽略迟到业务结果，不让旧任务的成功清零隔离状态。
Manager 的复查返回前还会核对 Entry、Runtime 对象和 revision，防止跨安装/跨运行误用检查结果。

## 8. 理解检查问题

1. 进程或端口存活，为什么不能直接证明公式解析能力可用？
2. 一个插件包两个 Provider，第二个样例失败时，第一个能否先发布？
3. 用户上传坏文档与引擎内部异常，为什么不能采用相同的隔离计数规则？
4. 隔离后收到旧任务的成功结果，为什么不能立即恢复？
5. Manager 为 ENABLED、Runtime 为 RUNNING，但健康状态是 ISOLATED，这三项分别说明什么？

## 验证

执行 `mvn -o -pl src/cloud-plugin-core -am test`：193 个测试全部通过，0 失败/错误/跳过；SPI 54，core 139，其中健康检查新增 14 个。验证范围为 SPI 与 core 的所有单元/并发测试。
新增覆盖整包发布前检查、失败清理、计划覆盖、跨插件隔离、故障分类、计数重置、重启身份、健康复查、断言失败、检查期间禁用、超时资源保留、重复检查、迟到成功以及排队/运行超时区别。
未运行后端全量测试、真实 OCR 或容器测试。

正确性自查：发布顺序、Entry/revision、健康状态独立性、终态隔离、排队任务门禁、结果迟到、预算共享、资源保留。
安全性自查：有界计划/输入/任务、固定错误消息、宿主断言信任边界、无 shell/网络执行、未将线程隔离描述为进程隔离。
