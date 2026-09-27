# cloud-plugin-core

本页保留 Phase 2 Registry 讲解；Phase 3 见 [Plugin Manifest](PHASE3-MANIFEST.md)，Phase 4 见 [Plugin Manager](PHASE4-MANAGER.md)，Phase 5 见 [Plugin Runtime](PHASE5-RUNTIME.md)，Phase 6 见 [健康检查与故障隔离](PHASE6-HEALTH.md)，当前 Phase 7 见 [管理 API](PHASE7-API.md)。

## Phase 2：Provider Registry

本模块是宿主侧实现，依赖 cloud-plugin-spi；插件作者仍只依赖 SPI。
Phase 7 已在 cloud-server 接入管理 API；仍未接入正式文档解析路径。Registry 只依赖 JDK 与 SPI；Phase 3 Manifest
读取器复用仓库管理的 Jackson。Phase 4 已加入进程内管理与整批注册；Phase 5 已加入通用 Runtime 和本地线程调度，真实 Docker/PaddleOCR 适配尚未实现。

## 1. 业务目标

Phase 1 约定“怎样调用一个 Provider”；Phase 2 解决“到哪里找到正确的 Provider”。
宿主显式登记已经创建好的实例，按 ID 与能力接口取回，或查询某种 MIME 的文档解析候选。
注册本身不代表安装完成、启用、健康、授权通过或支持特定语言/公式选项。

## 2. 核心抽象及职责

| 抽象 | 职责 |
| --- | --- |
| ProviderRegistry | 校验、保存、查找、列出及撤销进程内 Provider 注册 |
| Registration | 一次注册的凭证，保存身份、显式能力、文档 MIME 的不可变快照 |
| ProviderRegistryException | 区分元数据无效、重名、未找到、能力不匹配 |
| DocumentMediaType（SPI） | 请求与 Registry 共享具体 MIME 类型的校验和规范化规则 |

公共方法：

| 方法 | 行为 |
| --- | --- |
| register(capability, provider) | 注册并返回凭证；同一 Registry 内 ID 唯一，包括不同版本/能力 |
| find(capability, id) | 找不到或能力不匹配时返回 Optional.empty |
| require(capability, id) | 返回指定能力实例；未找到/能力不匹配使用不同错误码 |
| list(capability) | 返回该能力的身份快照列表，按 ID 排序且不可修改 |
| documentParsersFor(mediaType) | 返回支持具体 MIME 的候选，不自动选择、重试或回退 |
| unregister(registration) | 仅撤销该凭证对应的注册，不停止实例或释放资源 |

ID 是精确匹配，不做大小写折叠。每条注册发布一个明确的能力接口；可以按其父能力
查询，不能仅凭实现类还实现了另一个接口就自动发布那个能力。不同能力建议不同 Provider
ID；一个插件包仍可提供多条注册。Registry 不推断插件包归属，也没有持久化。

## 3. 完整调用链

实际测试验证的路径：

```text
测试宿主创建 ProviderRegistry 与示例 Provider
  → register(DocumentParserProvider.class, provider)
  → 校验能力接口和实例匹配
  → descriptor() + supportedMediaTypes()（各读取一次）
  → 验证并复制元数据
  → 检查 ID 冲突
  → 原子发布新名册，返回 Registration
  → documentParsersFor("text/plain") 查看候选
  → require(DocumentParserProvider.class, "parser.a")
  → 能力检查后返回原 Provider 实例
  → 调用方执行 provider.parse(request, context)
  → 测试 Provider 返回预设结果
  → unregister(registration) 撤销该条登记
```

调用测试只验证取回实例、参数与结果路径；预设文本不是 OCR 结果。Registry 从不调用
parse，也不打开文档内容。候选列表排序只保证稳定展示，不是优先级或自动择优规则。

查询候选和随后 require 是两次独立操作，中间可能撤销注册，调用方必须处理 NOT_FOUND。
单个读取操作看到一个完整快照；多个读取操作之间不承诺共享同一时刻。

## 4. 为什么采用当前设计

- 独立 cloud-plugin-core 防止把宿主注册实现放入插件作者的契约依赖。
- 显式登记能力接口，未来可注册其他 PluginProvider 子接口，无需强迫填写文档字段。
- 注册时固定元数据，避免查找过程中不断调用第三方方法或因其修改集合导致路由漂移。
- 写时复制不可变 Map，以 AtomicReference 原子发布；注册更新较少，查询多，读路径清晰。
- 重名直接失败，避免安装顺序或线程竞争悄悄决定最终实例。
- 用本次 Registration 对象身份撤销，旧凭证不能误删后来同 ID 的登记。

## 5. 替代设计及取舍

| 方案 | 优点 | 本阶段未选原因 |
| --- | --- | --- |
| ConcurrentHashMap + putIfAbsent | 写入成本低、并发操作成熟 | 多条目遍历是弱一致视图；这里优先让每次列表查询看到完整不可变快照 |
| 普通 HashMap + synchronized | 实现直接，适合较小负载 | 读写都需协调锁；当前读多写少，选择复制发布降低读路径复杂度 |
| Spring 自动注入所有 Provider | 启动接入方便 | 提前绑定框架和发现机制，难以单独演示动态登记与撤销 |
| 遇到重名直接覆盖 | 更新方便 | 隐藏冲突、破坏旧凭证与新实例之间的对应关系 |
| 固定不可变启动 Registry | 最简单，无并发写入 | 后续动态启停需要增删登记，本阶段先提供可验证的基础操作 |

## 6. 工程风险与边界

1. 写操作复制整个 Map，候选查询扫描并排序；适合数量有限且注册变更不频繁的场景。
   没有做大规模性能测试，高频注册应重新评估数据结构。
2. 元数据回调仍是第三方代码，可能阻塞或执行副作用。此阶段只捕获普通 RuntimeException，
   不捕获严重 JVM Error，也没有硬超时或沙箱。读取时虽不调用元数据，也不证明 Provider 安全。
3. 快照固定元数据，不能强制 Provider 实例本身不可变。实现若在注册后改变能力，声明可能
   与实际行为不符；正确做法是在未来受控生命周期内撤销并重新登记。
4. unregister 不等于 disable/stop/uninstall。已取得实例的线程不会因撤销登记自动停止。
   后续 Manager/Runtime 需要管理调用排空、禁用状态和资源释放。
5. 一个 Registry 对象就是一个名册，当前不跨进程/节点共享，不自动持久化或发现插件。
6. 只根据 MIME 筛选不代表支持识别语言或公式开关，真实解析仍须验证请求选项与字节。
7. 主机拥有注册/撤销权限；该 Java API 不承担身份认证或业务授权。异常原因链不可直接对外暴露。
8. 当前单条登记、全局 ID 唯一；不支持一个插件包的批量原子登记或同 ID 多版本并存。
   包级启用/回滚与版本选择需要后续设计，不能把 Registry 当成完整 Manager。
9. 本次没有连接真实 PaddleOCR、HTTP 服务、Docker 或当前 RAG 主链路。

## 7. 关键代码逐段解释

### 7.1 按业务能力登记

```java
var registry = new ProviderRegistry();
// provider 是已经创建好的 DocumentParserProvider 实现。
var registration = registry.register(DocumentParserProvider.class, provider);
DocumentParserProvider selected = registry.require(DocumentParserProvider.class, "parser.a");
```

Class 参数表达宿主想发布/取得的能力。编译器检查常规调用的类型，运行时也检查
isInstance，防止原始泛型调用绕过编译期约束。返回类型可直接调用 parse，不需手写强转。

### 7.2 注册前固定元数据

```java
ProviderDescriptor descriptor = Objects.requireNonNull(provider.descriptor(), "descriptor");
mediaTypes = Set.copyOf(((DocumentParserProvider) provider).supportedMediaTypes());
```

descriptor 是不可变 record，集合复制后成为独立快照。声明必须是非空、小写、无参数的
具体 MIME；查询输入可以规范化。元数据错误发生在发布之前，不会留下只注册了一半的条目。

### 7.3 原子发布与重试

```java
Map<String, Registration> current = entries.get();
Map<String, Registration> next = new HashMap<>(current);
next.put(id, registration);
if (entries.compareAndSet(current, Map.copyOf(next))) {
    return registration;
}
```

含义是：“只有名册仍等于我刚读到的那一版，才换成新版本。”若别的线程已经更新，
这次发布失败，循环重新读取并检查重名，保留对方的更新。校验插件元数据不放在重试循环内。

### 7.4 只提供显式发布的能力

```java
capability.isAssignableFrom(registration.capability)
```

检查请求接口是不是已发布接口本身或它的父接口，而不是检查实例碰巧实现了多少接口。
例如仅发布文档解析时，即使实现类同时实现测试用 GreetingProvider，也不能通过后者取回。

### 7.5 防止旧凭证误删新登记

```java
if (current.get(id) != registration) {
    return false;
}
```

比较这一次登记的对象身份。旧实例注销后，即使同一 Java 实例用同一 ID 再次注册，
也会获得新凭证；旧凭证不能删除新条目。其他 Registry 的凭证同样不能删除本名册的登记。

### 7.6 MIME 规则复用

DocumentParseRequest 与 Registry 都使用 DocumentMediaType。请求构造器的对外字段
仍是 String，原有规范化行为不变，避免两份正则日后出现“请求接受、注册表拒绝”的分歧。

## 8. 理解检查问题

1. SPI 已定义 parse 方法，为什么还需要 Registry？它解决的是哪一步的问题？
2. 两个 Provider 都支持 PDF，为什么候选列表不能默认选第一个？
3. 两个线程同时登记相同 ID，为什么先 containsKey 再普通 put 不够？当前代码怎样避免覆盖？
4. 为什么撤销注册要传 Registration，而不是只传 providerId？旧凭证会遇到什么问题？
5. unregister 成功后，已经拿到 Provider 实例的线程为什么仍能调用它？这要由后续哪一层处理？

## 验证与自查

执行 `mvn -o -pl src/cloud-plugin-core -am test`：SPI 54 个用例通过，Registry 28 个用例通过，
均为 0 失败、0 错误、0 跳过。Registry 包括 25 个行为用例执行项及 3 个八线程竞争测试。
测试覆盖元数据失败不发布、重名不覆盖、能力隔离、列表快照、共享 MIME 规则、调用参数、
旧凭证/跨 Registry 撤销，以及并发重名、不同 ID 写入和重复撤销。

第一轮自查：核对泛型边界、能力父子关系、元数据快照、重名策略、CAS 重试与撤销凭证。
第二轮自查：确认无外部命令/网络/业务数据操作，不执行 parse，异常信息不泄露回调细节；
明确注册不代表健康、授权或生命周期隔离。

阶段记录：Phase 2 已交付，用户随后授权 Phase 3；Manifest 说明见本页顶部链接。

根 Reactor 定向验证（2026-09-27）：6 个 Maven 项目均成功；SPI 54 个、Registry 28 个、
既有 HybridDocumentParserTest 2 个用例通过，均无失败/错误/跳过。未运行后端全量测试。
源码依赖与空白检查通过。

```powershell
mvn -o test '-Dtest=DocumentParserProviderContractTest,DocumentContractValueTest,DocumentOptionsAndFormulaTest,ProviderRegistryTest,ProviderRegistryConcurrencyTest,HybridDocumentParserTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

前端管理适配及逐段讲解见 [FRONTEND-INTEGRATION.md](FRONTEND-INTEGRATION.md)。
