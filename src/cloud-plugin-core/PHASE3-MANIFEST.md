# Phase 3：Plugin Manifest

状态：清单数据模型、严格 JSON 读取器、JSON Schema 与声明示例已实现。读取清单没有
安装、注册、启用或启动的副作用；未进入 Phase 4 Plugin Manager。

## 1. 业务目标

在没有启动插件之前，让宿主知道插件包是谁、发布哪些能力、声明哪些部署方式、是否匹配
当前 SPI，以及它提供的默认解析配置是否自洽。清单是说明书，不是运行实例。

## 2. 核心抽象及字段职责

| 抽象 | 职责 |
| --- | --- |
| PluginManifest | 插件包身份、版本、部署模式、包内 Provider 清单 |
| ProviderManifest | 单个 Provider 的 ID、实现版本、能力声明 |
| DocumentParserDeclaration | MIME/语言范围、是否支持表格和公式、默认选项 |
| PluginManifestReader | 有界读取 JSON，检查结构/版本/重复声明和字段关系 |
| PluginManifestException | 安全地区分读取失败、JSON 错误、声明错误和版本不兼容 |
| PluginSpi.VERSION | SPI 协议代号，目前为 1，不是 Maven artifact 版本 |

顶层字段：

| 字段 | 是否必填 | 原因 |
| --- | --- | --- |
| schemaVersion | 是，整数 1 | 判断宿主能否理解 JSON 结构 |
| id | 是 | 稳定标识插件包，与 Provider ID 分开 |
| name | 是 | 给人阅读的显示名 |
| version | 是 | 插件包版本，不在 V1 推断 SemVer 范围 |
| description | 否 | 可省略并读为空字符串；显式 null 不合法 |
| spiVersion | 是，整数 1 | 声明调用契约代号，宿主严格匹配 |
| runtimeModes | 是，非空且不重复 | DOCKER/LOCAL 部署方式声明，不代表已选择/启动 |
| providers | 是，1～64 项 | 一个包可以提供多条 Provider 声明，包内 ID 不重复 |

每条 providers 声明包含 id、version、capability、documentParsing。V1 的 capability 只接受
`document-parser`。Registry 能登记其他 Java 能力接口，不等于 V1 Manifest 已知道这些能力；
未来支持新能力需要显式扩展清单契约和宿主代码，不能根据任意 className 自动加载。

文档能力字段：

| 字段 | 含义与约束 |
| --- | --- |
| mediaTypes | 1～64 个不重复的小写具体 MIME，沿用 SPI 规则 |
| languages | 0～64 个不重复的规范化语言标签；空数组表示不接受显式语言选择 |
| supportsTables | 能否接受启用表格识别，不代表默认开启 |
| supportsFormulas | 能否接受启用公式识别，不代表默认开启 |
| defaultOptions.language | 必填，可为 null；非 null 时必须在 languages 中 |
| defaultOptions.recognizeTables | 必填 true/false；不能在 supportsTables=false 时为 true |
| defaultOptions.recognizeFormulas | 必填 true/false；不能在 supportsFormulas=false 时为 true |

与 Phase 1 请求的区别：单次请求开关允许 null，表示继承默认；Manifest 声明的两个默认
开关必须给出确定值。默认语言为 null 表示没有公开声明语言默认值，不代表自动检测。
对支持语言选择但未声明默认语言的实现，后续适配器仍需明确实际生效的引擎配置及缓存依据。

## 3. 完整调用链

```text
调用者打开 Manifest 输入流（调用者负责关闭）
  → PluginManifestReader.read
  → 最多读取 64 KiB + 1 字节，超限立即拒绝
  → Jackson 严格解析，拒绝重复键、尾随 JSON 和过深嵌套
  → 检查 schemaVersion 与 spiVersion
  → 检查字段名、字段类型、数组数量与重复值
  → 构造 ProviderManifest / DocumentParserDeclaration
  → 检查默认值是否超出支持能力
  → 构造不可变 PluginManifest
  → 返回说明书，或给出分类错误
```

整个过程不调用 Registry、不加载实现类、不请求模型、不读取环境变量、不启动容器。
未来 Manager 才负责将清单与实际 Provider 注册信息核对；本阶段不会把声明当作实际能力证明。

## 4. 为什么采用当前设计

- 采用版本化 JSON，字段类型清楚，能给作者提供 Schema 与示例，并复用项目已有 Jackson。
- 包版本、Provider 版本、SPI 版本、JSON 格式版本分开，避免用一个 version 承担四种含义。
- 严格拒绝未知字段，可以发现 recognizeFormula 等拼写错误，避免用户以为配置生效。
- 先做纯数据读取与校验，后续管理器可以在任何外部动作前得到完整、稳定的清单。
- records 与集合复制保证深层声明不可变；直接 Java 构造同样受关键关系约束。
- 当前是小型说明书，所以限制大小与数量，不将它当作任意配置、脚本或文档载体。

## 5. 替代方案

| 方案 | 好处 | 未采用原因 |
| --- | --- | --- |
| YAML | 可写注释，人工编辑便利 | 增加一种解析依赖和语义；当前先用 JSON + 中文字段文档 |
| 任意 Map 配置 | 灵活，添加字段快 | 类型错误、字段拼错与矛盾默认值难以稳定发现 |
| 直接反射反序列化全部 DTO | 代码短 | 默认类型转换、未知字段和版本处理容易混入隐式行为；显式树读取更便于教学与审查 |
| 只依靠 JSON Schema | 外部工具容易接入 | 默认语言属于支持集合、Provider ID 唯一性及重复 JSON 键还需独立处理 |
| 在 Manifest 放启动命令并直接执行 | 可以快速演示启动 | 跨越 Runtime 的职责，本阶段尚未确定 Python 本地承载和部署策略 |

## 6. 工程风险与边界

1. 能力是作者声明，不证明模型安装、格式质量、语言模型或表格/公式识别真实可用。
2. 支持 DOCKER/LOCAL 仅是部署意图。没有镜像、启动入口、端口、GPU、凭证或本地 Python
   进程配置，因此这个示例不是可以安装运行的发行包。相关执行声明需在 Runtime 阶段定稿。
3. schemaVersion/SPI 采用精确匹配，没有兼容区间、自动迁移或版本协商。声明兼容不等于
   已验证 JAR 二进制兼容；加载后仍需验证实际实现。
4. Manifest 的默认配置尚未自动注入 Provider；只有数据和一致性校验，没有配置应用器。
5. JSON Schema 提供结构校验；Java Reader 是本仓库接受清单的最终入口。跨字段集合关系、
   语言规范化、重复键和字节限制由 Reader 处理。Schema 的 integer 语义可能接受 1.0，
   Reader 则要求整数 token，不做浮点/字符串强制转换。
6. 64 KiB 输入、24 层嵌套、64 条 Provider 是当前明确限制。未来确有需求时应版本化调整。
7. 输入流有字节上限但没有 I/O 截止时间；调用者不得把不受控的阻塞网络流当作可无限等待的输入。
8. 异常原因链可能包含原始 JSON 片段，只能受控诊断，不能直接打印给用户或记录到普通日志。
9. 没有清单签名、包来源验证、依赖解析、安装事务、启停状态或健康检查；这些没有伪装成已实现。

## 7. 关键代码逐段解释

### 7.1 版本检查

```java
if (schemaVersion != PluginManifest.SCHEMA_VERSION) {
    throw new PluginManifestException(UNSUPPORTED_SCHEMA_VERSION, ...);
}
```

先判断说明书的格式是否认识，再解释字段。SPI 不兼容单独返回 INCOMPATIBLE_SPI_VERSION。
两者都不使用插件包 version 猜测兼容性。

### 7.2 有界读取

```java
bytes = input.readNBytes(MAX_BYTES + 1);
```

多读一个字节用于判定超限。读取完成后在内存中解析，不会让 JSON 解析器继续读取原始流。
原始流由打开它的调用者负责关闭，成功与失败路径都遵循同一所有权规则。

### 7.3 防止配置被悄悄转换

```java
if (!value.isBoolean()) {
    throw invalid(path + "." + field, "expected a boolean");
}
```

只有 JSON 的 true/false 才合法，字符串 "false"、数字 0 或 null 不会自动变成布尔值。
对象字段白名单同样不会忽略未知拼写。

### 7.4 能力与默认值的关系

```java
if ((!supportsTables && defaultOptions.recognizeTables())
        || (!supportsFormulas && defaultOptions.recognizeFormulas())) {
    throw new IllegalArgumentException(...);
}
```

不支持的能力不能默认启用。支持但默认关闭则完全合法，示例正是“支持表格识别但默认关闭”。
单次用户请求如何覆盖默认值仍属于后续配置应用流程。

### 7.5 包内身份与不可变数据

```java
providers = List.copyOf(providers);
if (!ids.add(provider.id())) {
    throw new IllegalArgumentException(...);
}
```

复制列表防止外部修改；按 ID 判断重复，即使两个条目的版本不同，也不能用同一 ID
在一个包里重复声明。这与 Registry 的 ID 唯一策略一致，但不意味着已经注册。

## 8. 理解检查问题

1. Manifest 和 Registry 都记录 Provider 信息，它们分别描述“声明”还是“实际实例”？
2. 为什么 schemaVersion、spiVersion 与插件 version 不能合并成一个字段？
3. supportsFormulas=true、defaultOptions.recognizeFormulas=false 是否矛盾？为什么？
4. 读取 Manifest 成功，能否证明插件已安装并可运行？还缺哪些步骤？
5. 为什么 "false" 字符串和拼错的字段要被拒绝，而不静默修正？

## 文件与验证

- 规范：`schemas/plugin-manifest.schema.json`，使用 JSON Schema Draft 2020-12。
- 示例：`schemas/examples/plugin-manifest.paddleocr.example.json`，名称和描述明确标注为契约示例。
- Java：本模块 manifest 包；不向 SPI 引入 Jackson。
- Schema 与示例作为 Maven resources 随 core 打包，Java 测试直接读取公开示例。

规范参考：[JSON Schema Validation 2020-12](https://json-schema.org/draft/2020-12/json-schema-validation)。
结构规范、语义检查和运行可用性是不同层次，不用 Schema 成功代替真实插件验证。

2026-09-27 验证记录：

- Manifest：60 个用例通过（Reader 57、值对象 3）。
- Registry：28 个回归用例通过。
- SPI：54 个回归用例通过。
- 既有 HybridDocumentParserTest：2 个定向回归用例通过。
- 根 Reactor 6 个 Maven 项目成功；未运行后端全量测试，未调用真实 PaddleOCR。
- Ajv 8.20.0 严格模式：Schema 编译、公开有效示例、8 份无效变体检查通过。
  首轮独立检查发现条件分支缺少显式 object 类型，已修正后重新通过。

Java 验证命令（仓库根目录）：

```powershell
mvn -o -pl src/cloud-plugin-core -am test
```

独立 Schema 验证（校验依赖仅安装到忽略的 target，未修改前端或生产依赖）：

```powershell
npm install --prefix src/cloud-plugin-core/target/schema-validation --no-audit --no-fund --ignore-scripts ajv@8.20.0
node src/cloud-plugin-core/src/test/js/plugin-manifest-schema.test.cjs
```

第一轮自查：字段所有权、版本差异、默认值关系、空值/数组/重复 ID、集合防御复制与资源所有权。
第二轮自查：输入上限、重复键、嵌套限制、未知字段、错误信息泄漏及无外部执行副作用。

Phase 3 当时的交付停止点保留于本页；后续已按授权完成 [Phase 4 Plugin Manager](PHASE4-MANAGER.md)。
