# Phase 1：Plugin Provider SPI

状态：实现了 Provider 契约及独立测试；已补充解析选项、独立公式和行内公式字段。
未接入真实 PaddleOCR。Phase 2 的宿主注册实现见 [cloud-plugin-core](../cloud-plugin-core/README.md)。新增字段规则见 [Phase 1 字段补充](PHASE1-OPTIONS.md)。

## 1. 业务目标

让 YLcloud 面向“文档解析能力”调用，让插件作者实现该能力。首个真实插件目标是
PaddleOCR；部署到 Docker 还是本地，不改变业务调用接口。

本模块是 Java 17 的普通 JAR，生产代码只依赖 JDK。它不依赖 Spring、数据库实体、
MinIO、Docker、HTTP 客户端，也没有 Spring Bean 自动注册。

本阶段只修改根 POM 的 modules 并新增 cloud-plugin-spi。既有 cloud-server 解析链路
保持独立；没有把测试中的纯文本示例当作已经完成的 OCR 插件。

## 2. 核心抽象及其职责

| 抽象 | 职责 |
| --- | --- |
| `PluginProvider` | 所有业务 Provider 的共同身份入口 |
| `ProviderDescriptor` | 稳定的 Provider ID 与实现版本；不是安装包 Manifest |
| `DocumentParserProvider` | 声明支持的 MIME 类型，同步解析文档 |
| `ProviderCallContext` | 单次调用的关联 ID、绝对截止时间及协作式中断检查 |
| `DocumentContent` | 宿主已授权的、可重复打开的同一份文档快照 |
| `DocumentParseRequest` | 文档标识、展示文件名、MIME、内容源、字节数与页数上限，以及解析选项 |
| `DocumentParseOptions` | 可选语言、表格和公式识别开关；未指定时沿用 Provider 默认值 |
| `FormulaContent` | 公式编码格式与源码，与普通文本区分 |
| `DocumentInline` | 段落/标题内部按顺序排列的文本或公式片段 |
| `DocumentParseResult` | 完整解析成功后的文本、阅读顺序块和诊断警告 |
| `DocumentBlock` | 文本类型、页码、标题路径、位置和可选置信度 |
| `BoundingBox` | 统一的归一化页面坐标 |
| `ProviderException` | 可分类的预期失败；不在 SPI 决定是否重试 |

Provider 是一个能力实现；未来一个插件包可以提供多个 Provider。包的安装、启停、
版本兼容检查、Provider 选择都不是这个接口的职责。新增模型、存储等能力时，应新增
能力专用接口，而不是给 `PluginProvider` 塞入所有能力的方法。

## 3. 完整调用链

当前可执行链路（契约测试，无网络、容器、数据库）：

```text
DocumentParserProviderContractTest
  → 创建 DocumentParseRequest + ProviderCallContext
  → 通过 DocumentParserProvider 引用调用 PlainTextProvider.parse
  → checkActive：检查中断与截止时间
  → 检查 MIME 支持情况
  → DocumentContent.openStream：打开授权快照
  → 在字节上限和调用期限内读取
  → 严格 UTF-8 解码（仅测试示例）
  → 构建 DocumentBlock 与 DocumentParseResult
  → try-with-resources 关闭输入流
  → 调用方收到结果，或 ProviderException / InterruptedException
```

非法 MIME、过期和已经中断的调用在打开输入前失败；读取失败、解析失败或读取中断后
也关闭已打开的流。空文档可以正常返回空文本，由宿主后续判断质量是否合格。

生产现有链路的相关片段仍为：

```text
SpaceRagService → DocumentParser / HybridDocumentParser
  → 按格式及质量选择具体解析器
  → 必要时 OcrStructuredParser
  → ParserServiceClient → /parse/ocr
  → Python document-parser-service（现有 Tesseract 路径）
```

未来接入方向（尚未实现）：

```text
宿主校验权限并固定文件快照
  → 后续 Registry / Manager 选择可用 Provider
  → DocumentParserProvider.parse
  → PaddleOCR 适配器
  → Docker 中的 Python 服务，或本地 Python 承载方式
  → 适配成 DocumentParseResult
  → 宿主映射成现有 RAG ParsedDocument 并执行质量策略
```

`DocumentContent` 是 Java 边界，不是 JSON 远程协议。远程适配器需要把流传输到服务，
不能直接把带有 openStream 方法的对象序列化发给 Python。

## 4. 为什么采用当前设计

- 独立 JAR 让插件作者只编译依赖契约；后端实体调整不会直接传染到插件接口。
- 通用身份 + 能力专用接口，让编译器检查文档解析的请求和结果。
- 同步方法明确流的寿命和调用完成的时刻。由后续 Runtime 安排线程、队列和隔离，
  不要求每位作者自行返回 Future 或创建不可追踪的后台任务。
- 流式内容端口支持内存、文件、对象存储和远程适配；不要求所有文档提前装进 byte[]。
- records 与 List.copyOf 固定成功结果，减少跨请求共享集合导致的并发污染。
- 异常表达失败，结果表达完整成功，避免 success=true 但正文是错误信息的歧义。
- 类型集合只声明真实支持的输入。“各类文档”是扩展目标，不等于所有格式自动支持。

## 5. 替代设计及未选原因

| 替代方案 | 好处 | 本阶段未选原因 |
| --- | --- | --- |
| 直接复用旧 DocumentParser | 改动少、能立即接上旧链路 | 输入绑定 File/SpaceFile 实体，输出可变；第三方耦合主服务模型 |
| 所有 Provider 都用 invoke(Map) | 新能力和字段变化自由 | 编译期无法检查请求/结果，错误推迟到运行时，学习和调试成本更高 |
| 只定义 HTTP JSON 接口 | Python 容器接入直接 | 把业务能力绑定到传输方式，本地 Java Provider 也被迫绕 HTTP |
| 所有输入都是 byte[] | 使用和测试简单 | 大文档必须整份进堆内存，还可能多次复制 |
| parse 返回 CompletableFuture | 调用方容易组合异步任务 | 提前绑定调度模型，流何时可关闭和取消传播更复杂；属于后续 Runtime 决策 |

## 6. 工程风险与边界

1. 接口契约不等于运行时防护。任意第三方实现可以忽略截止时间和限制；本阶段的
   checkActive 无法强制结束阻塞 I/O、Python 推理或 native 调用。
2. Docker 和本地运行均未实现。本地 Java 新线程能执行适配器调用，但不能直接
   代替 Python 解释器。Python 子进程、本地服务或嵌入式桥接的取舍留到 Phase 5。
3. 字节/页数上限需要真实 Provider 执行；测试仅证明示例的字节限制。模型输出、
   解压后体积、图像像素数、GPU 内存也需要后续独立限制。
4. 可重复读是宿主内容源的约定。传入路径背后的文件若中途改变，就不再是同一快照；
   宿主必须先固定版本并授权，而不能仅信任 documentId 或 MIME 字符串。
5. 请求 record 的内容源是行为对象；不能将请求宣称为深度不可变。结果对象和其
   嵌套集合则不可变。插件仍要处理自身模型或客户端的线程安全。
6. Provider ID 唯一性、声明类型集合合法性和适配版本的校验留到 Registry/Manifest。
   `ProviderDescriptor.version` 是实现版本，不能单独保证 SPI 二进制兼容或模型缓存有效。
7. 坐标只支持归一化轴对齐矩形；表格只保留类型及文本，不提供单元格合并、旋转多边形、
   富文本或附件模型。接入时必须明确如何转换，不能直接复用旧 bbox 字符串。
8. 错误原因链可能带私有诊断；宿主不能把异常栈直接暴露给用户。文档内容、文件名
   和 Provider 返回的文本都不能直接用作文件路径、命令或不经转义的 HTML。
9. 真实 PaddleOCR 格式支持、性能、资源释放及并发安全尚未验证。SPI 用例通过
   不能代替真实文档质量评测或 Docker/本地运行测试。

官方 PaddleOCR 快速开始提供 Python 与 CLI 调用示例：
[官方文档源码](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/quick_start.en.md)。
本阶段不选择或安装 PaddleOCR 版本、模型及推理引擎。

## 7. 关键代码逐段解释

### 7.1 身份接口

```java
public interface PluginProvider {
    ProviderDescriptor descriptor();
}
```

这个入口回答“谁提供了能力”。Descriptor 校验 ID 形式和非空实现版本。
它没有 install/start/stop 方法，因为 Provider 业务职责与插件生命周期分属不同层。

### 7.2 能力接口

```java
Set<String> supportedMediaTypes();
DocumentParseResult parse(DocumentParseRequest request, ProviderCallContext context)
        throws ProviderException, InterruptedException;
```

类型声明用于将来的路由；parse 仍要核验实际输入。异常声明提醒调用方分别处理业务失败
与取消；不能把中断吞掉后继续推理。这里没有规定 Docker 地址或进程启动命令。

### 7.3 输入所有权

```java
InputStream openStream() throws IOException;
```

宿主授权并持有底层快照；插件每次打开自己的流，并负责关闭。必须在 parse 返回前完成
所有读取，不能把源保存在字段中供稍后的后台任务使用。失败路径也遵循相同资源所有权。

### 7.4 协作取消

```java
if (Thread.currentThread().isInterrupted()) {
    throw new InterruptedException("provider call interrupted");
}
if (!Instant.now().isBefore(deadline)) {
    throw new ProviderException(ProviderException.Code.DEADLINE_EXCEEDED,
            "provider call deadline exceeded");
}
```

先检查中断，以保留取消语义；isInterrupted 不会清除标记。等于截止时间也算过期。
这些代码只有执行到时才生效，所以不能据此承诺硬超时或故障隔离。

### 7.5 成功结果的约束

```java
blocks = List.copyOf(blocks);
warnings = List.copyOf(warnings);
```

复制集合，避免作者后来修改原始 List 改写已返回的结果。DocumentBlock 内部同样复制
headingPath；其余成员是不可变值。结果构造器还要求块序号为 0、1、2……，明确阅读顺序。
BoundingBox 拒绝无穷、NaN、反向矩形和越界坐标；未知 confidence 用 null。

### 7.6 作者示例

`src/test/java/example/plugin/PlainTextProvider.java` 位于独立作者包中，仅导入 SPI 与 JDK。
它按“检查调用 → 校验格式 → 打开流 → 有界读取 → 解码 → 结构化返回”的顺序实现接口。
try-with-resources 保证异常路径也关闭流；超过上限直接抛出 LIMIT_EXCEEDED。
该类只存在于测试源码，不会被打进生产 JAR，也没有模拟成 PaddleOCR。

## 8. 理解检查问题

1. 为什么 Provider 不应该承担安装、启动容器和健康检查？把这些方法放进来会造成什么耦合？
2. 同一个 Provider 从本地切到 Docker，哪些部分应改变，哪些业务类型应保持不变？
3. 读取到一半超时或抛出异常时，谁关闭流？为什么不能把 DocumentContent 保存下来异步读取？
4. record 为什么不自动保证深度不可变？当前请求和结果在这一点上有什么区别？
5. checkActive 为什么无法强制停止卡住的 PaddleOCR？线程中断、HTTP 超时与进程隔离分别解决什么问题？

## 验证方法

在仓库根目录运行（-o 表示使用已有 Maven 缓存）：

```powershell
mvn -o -pl src/cloud-plugin-spi -am test
```

验收范围：作者独立实现、成功调用、重复打开、完整读取上限、错误分类、空文档、
严格解码、成功/失败/中断的资源关闭、过期与预中断拒绝、强制重叠的并发调用、
嵌套集合不可变、MIME/页码/坐标/置信度/块顺序约束。

首版验证记录（2026-09-27，新增选项/公式字段之前）：

- SPI 独立构建：33 个用例通过，0 失败、0 错误、0 跳过。
- 根 Reactor 定向验证：5 个 Maven 项目构建成功；SPI 33 个用例再次通过，
  cloud-server 的 HybridDocumentParserTest 2 个用例通过。未运行后端全量测试。
- 检查生产源码仅导入 JDK 与本模块类型；git diff --check 与新增文件空白检查通过。
- 正确性自查：核对身份/能力分层、成功与失败区分、流所有权、顺序/坐标/置信度边界。
- 安全性自查：无容器启动、外部命令、业务数据库操作及凭证传递；明确标注未来宿主的
  授权、资源隔离和异常脱敏责任。契约检查不是对不可信插件的沙箱。

根 Reactor 的定向验证命令：

```powershell
mvn -o test '-Dtest=DocumentParserProviderContractTest,DocumentContractValueTest,HybridDocumentParserTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

两次自查范围：第一轮检查接口职责、边界及资源所有权；第二轮检查授权内容边界、
敏感信息暴露、路径/命令执行与并发数据泄漏。当前无运行时外部依赖和业务数据访问。

阶段记录：Phase 1 已交付；用户随后授权 Phase 2，Registry 实现位于 cloud-plugin-core。
Manifest 已在用户授权后于 cloud-plugin-core 实现；Manager、Runtime、健康检查与故障隔离、管理 API 仍未实现。

字段补充后的验证（2026-09-27）：运行 `mvn -o -pl src/cloud-plugin-spi -am test`，
54 个用例通过，0 失败、0 错误、0 跳过；本轮未重跑后端定向回归或全量测试。

Phase 2 复用调整：DocumentParseRequest 的 MIME 校验提取为 DocumentMediaType，字段和规范化行为不变。

Phase 3 补充：PluginSpi.VERSION 声明 SPI 协议代号，供 Manifest 兼容检查使用，不等于 Maven artifact 版本。
