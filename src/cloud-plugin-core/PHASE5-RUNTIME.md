# Phase 5：Plugin Runtime

本页保留 Phase 5 通用 Runtime 的阶段讲解。后续已按授权完成 [Phase 6 健康检查与故障隔离](PHASE6-HEALTH.md)；原四参数工厂 enable 已明确改名 enableUnverified，新的带 HealthPlan 启用入口先验证再发布。
Docker 后端目前是启动/关闭工厂契约，没有执行真实容器；没有安装或运行 PaddleOCR、Python、OCR 模型。

## 1. 业务目标

将已经定义的业务能力放到可管理的运行生命周期中：启动并持有资源，注册受控 Provider，限制并发与排队，传播截止时间，禁用后拒绝旧引用的新调用，并在工作线程真正退出后释放资源。

Manager 的 ENABLED/DISABLED 表示是否发布；Runtime 的 STARTING/RUNNING/STOPPING/STOP_FAILED/STOPPED 表示本轮运行的生命周期。
RUNNING 不代表健康检查通过。STARTING 期间 Manager 仍是 DISABLED，因此不能单看 DISABLED 就认为资源已经停止。

## 2. 核心抽象及字段

| 抽象/字段 | 职责 |
| --- | --- |
| PluginRuntimeFactory.start(manifest, mode) | 可信宿主选择 LOCAL 或 DOCKER 适配器并创建资源，返回一次运行会话 |
| Session.providers() | 返回本轮创建的实际 Provider；返回后资源所有权移交给 Runtime |
| Session.close() | 释放该会话拥有的资源；适配器负责关闭 I/O 超时，失败可幂等重试 |
| PluginRuntime | 持有会话、包装实例、有界线程池及停止状态 |
| Limits.workers | 每包工作线程数，1～32 |
| Limits.queueCapacity | 每包等待队列容量，1～1024；饱和返回 UNAVAILABLE，不另开无界线程 |
| Limits.maxSnapshotBytes | 单文档内存快照上限；默认 16 MiB，可配置 1 字节～64 MiB |
| admissions | 对执行、排队和快照准备统一限制，总槽位为 workers + queueCapacity |
| gate | 在同一锁下决定接收任务或关闭入口，消除禁用与提交的竞态 |
| startFinished | 区分“已请求停止”和“启动回调已经返回”，防止提前丢失会话资源 |
| cleanup | 串行化资源关闭，避免多个等待者重复关闭成功释放的资源 |
| Entry.runtime | 在启动前保存本轮 Runtime，失败后仍可追踪并重试清理 |

工厂属于宿主 core 扩展点，插件业务实现仍面向 SPI。Manifest 不存放任意 shell 命令。
当前不根据任意类名反射加载插件；LOCAL 工厂可以创建 Java 本地实现，Python PaddleOCR 仍需要独立适配器。
DOCKER 工厂将来负责容器/连接，Runtime 工作线程运行它提供的代理调用。通用线程池不等于容器隔离。

## 3. 完整调用链

### 启用

宿主 → Manager.enable(id, mode, factory, limits)
→ 锁内确认旧 Runtime 已停止，创建并关联新 Runtime，revision++，记住 Entry 身份与 revision
→ 锁外 Runtime.start(factory)
→ factory.start 返回 Session → 读取实际 Provider 元数据 → 构造受控包装对象
→ Manager 核对 Manifest、Entry 身份与 revision → Registry 整包发布包装对象 → ENABLED。

工厂启动或发布失败 → 关闭入口 → 尝试零等待排空并释放资源。
清理未完成或失败时仍在 Entry 保存 Runtime，拒绝重新启用/卸载，允许 awaitStopped 重试。
工厂抛出异常前尚未返回 Session 的资源由工厂自行清理；返回 Session 后由 Runtime 清理。

### 业务调用

Registry.require → 包装 Provider.parse(request, context)
→ 检查调用截止时间、MIME 和有效选项
→ gate 检查 RUNNING 并占用准入槽位
→ 同步读取有大小上限的宿主文档快照并关闭原始流
→ 再次检查入口 → 提交 FutureTask 到独立线程池
→ 工作线程检查原截止时间 → 实际 Provider.parse(自有快照, context)
→ 检查返回值及截止时间 → 调用方拿到结果。

Manifest 默认值与单次选项的合并：非 null 请求值优先，否则继承 Manifest。false 是明确关闭，不能被默认 true 覆盖。
不支持的语言/开启选项在读内容之前拒绝；这只是依据声明校验，不证明模型实际支持。

截止时间也覆盖排队。超时或调用方中断 → Future.cancel(true)，已排队任务移出队列；正在执行的任务收到协作中断。
不会自动重试，不会把晚到结果当作成功。运行中任务的槽位直到执行线程真正退出才释放。

### 禁用和停止

Manager.disable → Runtime.stopAccepting 关闭提交入口 → Registry 整批撤销 → Manager 状态 DISABLED。
旧引用仍指向包装实例，但同一个 gate 已关闭，它不能提交新任务。
已经接收的任务采用优雅排空，包括排队任务；每个任务仍遵守自己的调用截止时间。

Manager.awaitStopped(id, budget)
→ 若尚有活跃 Runtime，锁内递增 revision 并关闭入口，防止 STARTING 中的旧启用继续发布
→ 等待启动回调返回 → 等待线程池真实终止 → Session.close → STOPPED。
返回 false 表示仍未完成排空，必须稍后重试；关闭异常表示 STOP_FAILED，保留会话以便重试。
只有 STOPPED 才允许新一轮受管启用或卸载。每次重启创建新的 Runtime，旧引用永远不会重新开放。

## 4. 为什么采用当前设计

将 Provider 包装在 Runtime 入口后，即使业务线程早先取得了引用，禁用也能拦住它的新调用。
把资源所有权保留到实际线程退出，避免 native/OCR 引擎还在使用资源时被另一线程关闭。

有界线程池适配同步 SPI，业务调用方仍同步等待，不把 Future 类型侵入插件接口。
用有界内存快照维护 SPI 的内容生命周期：外层调用超时返回后，不合作的工作线程只能访问自有字节，不能重新读取已经失效的宿主流。

身份检查沿用 Entry + revision；受管 Runtime 在启动前就关联到 Entry，启动期间禁用不会产生无人负责的孤儿 Session。

## 5. 替代方案

1. 把裸 Provider 直接注册：开销小，但旧引用绕过禁用。保留 Phase 4 Map 重载仅作调用方自管资源的低层兼容入口，业务接入应使用工厂重载。
2. 每次调用创建新线程：实现短，但负载升高或插件卡住时线程数失控。使用每包固定线程数和有界等待队列。
3. 超时立即关闭 Session 或强杀 Java 线程：可能在任务仍执行时释放资源，破坏共享状态。采用协作中断、确认线程终止后释放；强隔离依赖后续进程/容器适配。
4. 超时后让工作线程继续读原始 DocumentContent：无需拷贝，但违反宿主快照只保证到 parse 返回的契约。选择内存快照；大型文档的磁盘快照和配额可以以后增加。

## 6. 工程风险与边界

- Factory.start、元数据回调、Session.close 和宿主 InputStream 的底层阻塞无法被普通 Java 线程强制终止。适配器必须设置自身 I/O 超时。
- awaitStopped 的 budget 只限制启动完成及工作线程排空等待，不是 Session.close 的强制总超时；同时等待 cleanup 锁也不受该 budget 强制限制。
- 不响应中断的任务会继续持有线程、准入槽位和快照。Runtime 保持 STOPPING 并阻止重启/卸载，不能声称停止成功。线程池不是进程级安全隔离。
- 本地模式只允许可信代码；没有沙箱、类加载隔离、系统资源配额或跨进程崩溃恢复。健康检测、熔断和能力样例验证留到 Phase 6。
- 内存开销随 workers + queueCapacity 和 maxSnapshotBytes 增长，复制时存在临时缓冲。宿主应按总内存配置合理值；多个插件没有统一内存配额。输入超过宿主 maxInputBytes 或 Runtime 上限均明确 LIMIT_EXCEEDED。
- 文件读取在提交工作线程前同步执行，循环检查截止时间，但不能强制打断阻塞的底层 read。宿主应提供有时限的快照读取方式。
- 快照只保留原始字节，maxPages 等业务限制仍由 Provider 验证。输出大小没有新增通用硬上限。
- 禁用只负责关闭入口和撤销名册；调用方必须继续 awaitStopped，释放并不由后台自动完成。停止超时后调用方需安排重试。
- Map 版 enable 仍保留旧行为，裸 Provider 引用不受 Runtime 门禁约束。不能把它当成受管业务启用入口。
- 失败原因作为 cause/suppressed 保存，仅供受控诊断，不应直接返回 HTTP 响应或无筛选记录日志。
- Docker 只有 Factory/Session 契约和模式传递测试，未提供 Docker CLI/HTTP 实现。没有真实 PaddleOCR 功能与精度验证。

## 7. 关键代码逐段解释

### 先关联，再启动

```java
runtime = new PluginRuntime(expected.manifest, mode, limits);
expected.runtime = runtime;
revision = ++expected.revision;
```

Manager 锁内执行。其他线程此时可以发现 STARTING 的 Runtime 并请求停止，也无法覆盖或卸载这份未释放的运行资源。
随后在锁外调用工厂，避免插件启动阻塞所有管理操作。发布前仍核对 Entry 身份和 revision。

### 关闭旧引用的入口

```java
synchronized (gate) {
    if (state != State.RUNNING) { throw unavailable(...); }
    if (!admissions.tryAcquire()) { throw unavailable(...); }
}
```

取得槽位不表示已经调用 Provider。读取快照后提交前再次检查 gate，禁用期间完成快照的调用也不能偷偷提交。
stopAccepting 用同一 gate 修改状态并关闭线程池提交。

### 明确值覆盖默认值

```java
boolean formulas = options.recognizeFormulas() != null
        ? options.recognizeFormulas() : defaults.recognizeFormulas();
```

请求 false 会保留 false；只有 null 才取默认值。不支持的 true 直接失败，不能静默忽略。

### 保护输入生命周期

```java
var owned = snapshot(effective, context);
```

最多读取有效上限加一个字节来识别超限，关闭原始流。任务只捕获 owned，其 content 每次从自有字节创建新流。
超时后即使引擎继续运行，也不会接触调用方原始 source。

### 取消与退出分开

```java
task.cancel(true);
executor.remove(task);
```

Future 已取消只说明调用方不再等待结果，不保证任务体已经结束。
队列取消在 done 归还槽位；执行中的取消等 run 的 finally 归还，原子标记防止重复归还。

### 资源释放的顺序

```java
startFinished.await(...);
executor.awaitTermination(...);
session.close();
```

代码逐步检查等待结果；任何等待未完成即返回 false，不调用 close。
关闭成功只做一次；关闭失败记录 STOP_FAILED 并保留资源，下一次 awaitStopped 可重试。

## 8. 理解检查问题

1. 为什么 Registry 要保存受控包装实例，不能只在 disable 时删掉裸实例的登记？
2. Future.cancel(true) 返回后，为什么不能立即关闭引擎或容器资源？
3. Manager 已经是 DISABLED，为什么 Runtime 仍可能是 STOPPING，且不允许卸载？
4. 请求 recognizeFormulas=false，Manifest 默认为 true，实际传给 Provider 的是什么？
5. 调用超时后任务可能还在执行，为什么要让它读取 Runtime 自有快照？

## 验证与审查

执行 `mvn -o -pl src/cloud-plugin-core -am test`：179 个测试全部通过，0 失败/错误/跳过；SPI 54，core 125（其中 Runtime 新增 18）。
验证包括真实 Java 工作线程、旧引用禁用、重启不复活旧引用、排队上限、排队取消、调用方中断、不合作任务超时、启动期间禁用、发布失败释放、清理失败保留与重试、输入流关闭/快照上限/快照有效期及部署模式传递。
Docker 模式测试使用 Session 替身，不是容器测试。未执行后端全量测试或真实 OCR。

第一轮正确性审查：启动资源归属、Entry/revision、门禁与提交竞态、取消与执行退出差异、队列配额释放、输入快照生命周期。
第二轮安全性审查：有界线程/队列/输入、错误文本不含私有异常、无 shell/网络启动指令、无提前关闭、无不受控后台重试；输入快照风险已修复并补充回归。
