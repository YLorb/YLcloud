# Phase 4：Plugin Manager

本页保留 Phase 4 的阶段讲解；后续已按授权完成 [Phase 5 Runtime](PHASE5-RUNTIME.md)。下文对裸实例的说明对应 Map 版 enable；新增工厂版提供 Runtime 门禁和资源管理。

## 1. 业务目标

将一份 Manifest 中的多个 Provider 作为一个插件包管理：登记说明书、启用已有实例、禁用登记、卸载说明书。
整包启用失败不能暴露半个包。撤销后 Registry 的新查询找不到本包实例，但已取得的引用仍可使用。
当前方法是宿主内部 Java API；HTTP API、身份鉴权及安装包操作属于后续阶段。

状态只有 DISABLED、ENABLED。未安装表示没有记录，不额外保存 UNINSTALLED。
ENABLED 只表示已向 Registry 发布，不表示模型健康或 Docker 已启动。

## 2. 核心抽象及职责

| 抽象/字段 | 职责和存在原因 |
| --- | --- |
| PluginManager | 以插件包为单位协调 Manifest、归属、管理状态和 Registry |
| Entry.manifest | 保存不可变声明，作为核对实际实例的基准 |
| Entry.state | 表达是否发布能力，避免重复启用或启用中直接卸载 |
| Entry.mode | 记录本次选择的声明部署方式；仅 ENABLED 有值，不证明实际运行方式 |
| Entry.revision | 每次成功启用/禁用递增，使锁外准备的旧操作失效 |
| Entry.registrations | 保存本次实际注册凭证，只撤销本包拥有的注册 |
| providerOwners | 在本 Manager 内保留已安装包的 Provider ID 归属，禁用不释放，卸载才释放 |
| Snapshot | 对外只读视图，包含 manifest/state/mode/revision，不暴露可变 Entry 和凭证 |
| PreparedRegistration | 元数据采集结果；尚未发布，也不是撤销凭证 |
| PluginManagerException | 区分未安装、重复安装、归属冲突、状态错误、声明不符、部署不支持和并发变更 |

Manager 当前理解 DOCUMENT_PARSER，未来新增业务能力要扩展 Manifest 能力声明与启用分派。
不应把 OCR 专用字段强加给所有未来能力。

## 3. 完整调用链

登记：宿主提供调用方负责关闭的 InputStream → PluginManifestReader.read → PluginManifest
→ PluginManager.install → 检查插件 ID/包间 Provider ID 归属 → 保存 DISABLED 快照。
install 不拥有输入流，不下载或解压插件。直接传入合法构造的 PluginManifest 也可以登记。

启用：宿主提供已创建、已配置的实例 Map → PluginManager.enable(pluginId, mode, instances)
→ 锁内检查已安装/禁用/部署声明，保存 Entry 身份与 revision
→ 锁外核对 Provider ID 集合、接口，并调用 Registry.prepare
→ prepare 调用 descriptor、supportedMediaTypes 各一次并复制元数据
→ Manager 核对 ID、版本、MIME 必须完全一致
→ 锁内检查 Entry 身份与 revision 未变
→ Registry.registerAll 检查全部重复 ID、一次 CAS 发布整批条目
→ 保存注册凭证、mode，切为 ENABLED 并递增 revision → 返回 Snapshot。

业务：Registry.documentParsersFor(MIME) → 用户选择候选或使用其明确默认 ID
→ Registry.require(DocumentParserProvider.class, providerId) → provider.parse(request, context)。
Manager 不代理 parse，也不自动选择第一个候选。

禁用：PluginManager.disable → Registry.unregisterAll(当前凭证)
→ 一次 CAS 撤销仍属于本包的注册 → 清空凭证和 mode → DISABLED，revision++。
重复禁用也递增 revision，取消已经开始、仍在锁外读取元数据的启用。

卸载：PluginManager.uninstall → 必须 DISABLED → 删除 Entry → 释放 Provider ID 归属。
以后同 ID 重装会创建新 Entry；即使 revision 又是 0，旧启用也会因为身份不同被拒绝。

## 4. 为什么采用当前设计

管理操作频率低，使用短 synchronized 临界区，让状态和归属检查容易推理。
插件提供的方法在锁外执行，某个插件的元数据回调卡住时，其他管理操作仍可执行。
这里没有线程超时或强制取消保证，需后续 Runtime 处理。

Registry 将整批加入和整批撤销各自放在一次 CAS 内：读取一份 Registry 快照不会看到半个包。
Manager 在同一临界区完成发布和状态更新；Manager.get/list 不会看到内部更新中间态。
跨 Manager/Registry 的两次独立查询不是数据库事务，中间仍可能发生其他管理操作。

登记和执行分离：本阶段已经可以测试状态及并发规则，后续 Runtime 负责创建/配置/停止实例后再接入。
当前模式参数由可信宿主传入，只验证是否在 Manifest 的 runtimeModes 中。

## 5. 替代设计

1. 逐个 register，失败再逐个 unregister 回滚：代码短，但第一个 Provider 已经可能被业务线程取走；回滚不能撤销这次可见性。选择整批 CAS。
2. 在 Manager 全局锁内调用插件元数据或启动 Docker：状态实现简单，但慢插件会阻塞其他插件管理，回调还可能产生锁依赖。选择锁外准备、锁内复核。
3. 直接做持久化状态机和异步事件编排：适合重启恢复、多节点及长时间部署，但会提前引入 Runtime、数据库迁移和任务恢复。本阶段只建立进程内规则。

## 6. 当前工程风险与边界

- 重启丢失登记和状态；没有持久化、多节点协调、升级和版本共存。
- 宿主必须让 Manager 独占所管理 ID 的 Registry 写操作。保留底层 Registry 公共写接口用于独立使用和测试，当前未用权限对象强制隔离。绕过 Manager 修改名册可能导致管理状态与实际条目不一致。
- providerOwners 只在一个 Manager 内保留 ID。另一个 Manager 或直接注册者的冲突会在 enable 的整批提交阶段被拒绝，install 不预留底层 Registry 槽位。
- 元数据调用约定无 I/O、稳定，但没有强制执行时间上限。插件实例应由可信宿主提供，不能把任意上传代码直接交给 Manager。
- 语言、表格、公式只有 Manifest 声明，当前 SPI 无对应实际能力查询接口，不能核实真实模型是否支持。
- Manager 不把 defaultOptions 自动配置到实例，也不改写 parse 请求。当前实例由宿主预先配置，SPI 实现应用请求覆盖默认值；后续 Runtime 必须落实 Manifest 默认配置的传递。
- 禁用不停止现有实例，已持有引用仍可发起新调用；启用失败也不释放传入实例。当前资源所有者仍是调用方，后续 Runtime 需要负责失败清理、调用排空和停止。
- mode 是选择记录，不能据此声称实际启动了 Docker 或本地线程。没有执行真实 PaddleOCR，没有健康检查。
- 普通注册冲突或元数据失败不改变禁用状态；进程崩溃、内存耗尽等灾难性错误不在内存事务保证范围内。

## 7. 关键代码逐段解释

代码位于 src/main/java/com/ylcloud/plugin/manager/PluginManager.java
与 src/main/java/com/ylcloud/plugin/registry/ProviderRegistry.java。

install：先遍历全部声明检查归属，再写入 plugins/providerOwners。
因此第二个 Provider 冲突时，第一个 Provider 不会留下错误的归属记录。

```java
expected = requireEntry(pluginId);
revision = expected.revision;
```

保存的 Entry 表示这一次安装；revision 表示这一次安装内的管理修订号。
两者不是插件版本、SPI 版本或 Manifest 格式版本。

```java
var candidate = registry.prepare(DocumentParserProvider.class, parser);
```

此处仅采集声明，不让业务线程查到实例。Manager 用同一份快照核对 Manifest，
随后 Registry 发布这份已核对元数据，避免插件两次返回不同声明。

```java
if (plugins.get(pluginId) != expected || expected.revision != revision) {
    throw failure(CONCURRENT_CHANGE, "plugin changed during enable preparation");
}
```

身份变化表示原来的安装记录已卸载；revision 变化表示期间发生过启用或禁用。
任一成立都拒绝旧操作，不用“当前恰好仍是 DISABLED”作为充分条件。

```java
expected.registrations = registry.registerAll(prepared);
expected.mode = mode;
expected.state = State.ENABLED;
expected.revision++;
```

只有整包发布成功才修改状态。Registry 普通冲突异常发生时状态保持原样。
registerAll 每次产生新凭证，即使复用同一 PreparedRegistration，旧凭证也不能删掉新注册。

```java
if (entries.compareAndSet(current, Map.copyOf(next))) { ... }
```

发布点只有这一处。其他写线程先修改了 current，则基于最新快照重新检查冲突并重试。
列表查询读取一次 Map，得到零个或全部新增条目。

```java
registry.unregisterAll(entry.registrations);
entry.revision++;
```

撤销检查凭证身份，跳过已经失效的凭证，保留其他注册者的替代实例。
revision++ 使正在锁外准备的旧 enable 无法提交，即使此次 disable 原本已经是禁用状态。

## 8. 理解检查问题

1. 一个插件声明两个 Provider，第二个 ID 已被占用，为什么第一个也不能先发布？
2. enable 正在读取实例元数据时用户调用 disable，revision 如何阻止旧 enable 随后生效？
3. 卸载后用相同 ID 重新安装，revision 又是 0，为什么还要比较 Entry 的对象身份？
4. ENABLED 能否说明 PaddleOCR 模型健康、Docker 已启动？当前它实际保证了什么？
5. 禁用之后，早先拿到 Provider 引用的线程还能调用 parse 吗？后续 Runtime 要补哪部分？

## 验证

执行 `mvn -o -pl src/cloud-plugin-core -am test`：161 个测试通过，0 失败/错误/跳过。
SPI 54；Registry 原有 28 + 整批新增 5；Manifest 60；Manager 新增 14。
本阶段新增 19 个测试覆盖生命周期、失败原子性、旧凭证、回调期间禁用/卸载重装、同时启用和整批可见性。
没有运行后端全量测试；未改动正式文档解析接入路径，未验证真实模型或容器。

第一轮自查：状态与 ID 归属、整包失败原子性、不可变快照、重复启用、并发取消和同 ID 重装。
第二轮自查：插件回调不持管理锁、稳定异常消息、凭证身份校验、资源所有权、没有文件或外部进程执行。
