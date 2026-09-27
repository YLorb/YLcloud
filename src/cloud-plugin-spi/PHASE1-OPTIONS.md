# Phase 1 字段补充：按需配置与结构化公式

## 1. 业务目标

允许宿主按次指定识别语言及表格/公式识别选项；允许作者返回独立公式，以及不破坏
句子顺序的行内公式。普通文本调用继续使用原来的简便构造器。

本次是 SPI 字段和约束更新，没有加入 Registry、配置持久化、模型启动或真实 OCR。

## 2. 核心抽象及字段职责

| 字段 | 填写方 | 何时使用 | 未填写的含义 |
| --- | --- | --- | --- |
| request.options.language | 宿主 | 本次需要覆盖 Provider 默认语言 | null 沿用默认，不代表自动检测 |
| request.options.recognizeTables | 宿主 | 本次明确开/关表格识别 | null 沿用默认，false 关闭，true 开启 |
| request.options.recognizeFormulas | 宿主 | 本次明确开/关公式识别 | null 沿用默认，false 关闭，true 开启 |
| block.formula | 作者实现 | 独立 FORMULA 块 | 非公式块必须为 null；公式块不可缺失 |
| formula.format | 作者实现 | 存在公式内容时 | 必填；当前仅 LATEX |
| formula.source | 作者实现 | 存在公式内容时 | 必填非空，LaTeX 公式正文，不含外围数学定界符 |
| block.inlines | 作者实现 | 拆分段落/标题中的文字与公式 | 空列表表示未提供内部拆分 |
| inline.type | 作者实现 | 提供行内片段时 | 必填，TEXT 或 FORMULA |
| inline.text | 作者实现 | 提供行内片段时 | 必填，是该片段供搜索/回退显示的文本 |
| inline.formula | 作者实现 | 行内 FORMULA 片段 | 公式片段必填；文字片段必须为 null |

语言使用引擎无关标签，例如 en、zh-CN、zh-Hans；这里只验证语法并规范化大小写。
标签合法不等于引擎支持，Provider 负责映射、验证与拒绝不支持的配置。

“按需使用”并不代表所有字段随便填空。通用必填数据始终存在；新增选项允许未指定；
条件字段在相应类型下必须满足约束。

## 3. 完整调用链

实际测试调用：创建带 options 的请求 → DocumentParserProvider → 示例 Provider 检查
调用状态、格式与选项 → 支持时读取内容并返回 → 不支持时在打开流前抛出
UNSUPPORTED_OPTION。示例不做语言识别，显式语言与识别开启请求会被拒绝；false 开关
可被遵守，因为示例本来就不执行表格/公式识别。

公式结果测试：创建 FormulaContent → 构造独立 FORMULA 块，或 DocumentInline 列表
→ DocumentBlock 检查适用类型、内容存在性、文本一致性并复制列表 → 调用方读取。
这是结果契约验证，没有真实 OCR 引擎生成公式。

未来真实适配器：请求选项覆盖 Provider 默认配置 → 映射引擎参数 → 识别 → 转换公式源码
和文本表示 → 生成上述块结构。默认配置存储与模型选择尚未实现。

## 4. 为什么这样设计

- 选项统一放入 DocumentParseOptions，不混入所有 Provider 的共同身份。
- Boolean 保留 null/true/false 三种状态，避免没指定就意外关闭默认功能。
- FormulaContent 显式保存格式，避免把 LaTeX 与普通文本混在 text 中。
- 公式放在独立块还是行内片段由文档结构决定，保留阅读关系。
- 有 inlines 时，其 text 串联必须等于 block.text，避免检索文本与结构化正文矛盾。

## 5. 替代设计

| 方案 | 未采用原因 |
| --- | --- |
| 所有选项放 Map | 拼写/类型错误推迟到运行时，缺少明确的默认值语义 |
| 使用 primitive boolean 开关 | 无法区分未指定与明确关闭 |
| 将所有公式抽成顶层块 | 破坏句子内部的文字/公式顺序 |
| 直接把 LaTeX 填入通用 text | 消费方无法明确区分纯文本与待渲染源码 |

## 6. 工程风险

- options 只是契约，不能强制任意实现遵守。没有引擎的真实语言映射和识别结果验证。
- 识别开关控制识别功能，不把原生格式中已提取的结构化公式/表格一律删除。
- 没有公式语法正确性检查、数学正确性验证或渲染器；源码按不可信内容处理。
- 表格单元格及其中的公式未建模，不能把单元格序列伪装成普通段落的 inlines。
- 保留原请求及普通块构造器；旧 FORMULA 块若未提供 payload，将不再合法。
  record 组件变化也不意味着已有序列化客户端或二进制消费者自动兼容。
- inlines 可选；提供时是整段的有序分解，不能只挑公式片段而遗漏前后正文。
- 单一 language 是一次识别配置，不表示可任意同时选择多种语言；混合语言能力由
  Provider/模型决定。未来缓存必须区分实际生效的语言与识别选项。

## 7. 关键代码解释

```java
new DocumentParseOptions(null, null, null); // 全部继承 Provider 默认值
new DocumentParseOptions("zh-CN", false, true); // 中文配置、关闭表格、开启公式
```

null 是未指定，false 是明确关闭。options 对象本身不能为 null；原来的六参数请求
构造器会自动使用 defaults()。因此普通调用无需填写新增参数。

```java
new FormulaContent(FormulaContent.Format.LATEX, "x^{2}");
```

format 告诉消费者如何解释 source，text 另存面向检索/回退显示的表示，例如 x²。

```java
if ((type == Type.FORMULA) != (formula != null)) {
    throw new IllegalArgumentException("only FORMULA blocks require formula content");
}
```

两边必须同时为真或同时为假：公式块必须带内容，其他块不得误填独立公式内容。
行内片段有相同约束。

```java
inlines = List.copyOf(inlines);
```

固定片段的顺序与内容引用；片段和公式自身也是不可变值。块构造器再验证片段文本
拼接后恰好等于正文，确保结构信息没有悄悄改变文本。

## 8. 理解检查

1. 未指定 recognizeFormulas 和明确设置 false 有什么区别？
2. 为什么 TEXT 块的顶层 formula 必须为空，却允许它的 inlines 里有公式？
3. 为什么 zh-CN 的格式合法，并不意味着每个 Provider 都支持它？

## 验证记录

2026-09-27：`mvn -o -pl src/cloud-plugin-spi -am test` 成功；54 个用例，
0 失败、0 错误、0 跳过。本次新增 21 个用例执行项；没有真实 PaddleOCR 或后端全量测试。

正确性自查：默认值与显式关闭分离、原普通构造方式可用、公式适用类型、行内阅读顺序、
嵌套集合不可变、引擎不支持选项时拒绝。
安全性自查：未新增网络/进程/文件执行功能，公式源码仅是数据；解析语言不能静默回退，
错误信息不包含文档内容；真实 Provider 的执行隔离与渲染安全仍属于后续接入责任。

阶段仍为 Phase 1，未进入 Phase 2。
