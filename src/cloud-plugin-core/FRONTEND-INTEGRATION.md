# 插件管理前端适配

## 1. 业务目标
部署所有者通过 `/admin/plugins` 登记说明书、选择已配置的运行模式、启用、检查健康、禁用清理、卸载登记。明确显示管理、运行、健康三种状态，避免把登记或停止请求误认为部署完成。

## 2. 核心抽象及职责
- `pluginApi.ts`：描述服务端 View，封装六种操作及成功码；它不生成 Provider，也不执行引擎。
- `PluginsPage`：权限门禁；内部页面管理查询缓存、操作确认与请求后的状态刷新。
- `PluginItem`：把三个状态翻译成用户能理解的文字，并决定按钮是否可用。
- `ManifestDialog`：读取不超过 64 KiB 的说明书，原文提交，保留服务端错误和输入。
- `PluginActionDialog`：确认操作、选择已配置模式、等待结果及展示错误。

## 3. 完整调用链
会话恢复 → AdminRoute → AdminLayout → PluginsPage 所有者检查 → GET 列表 → PluginItem。
用户点击操作 → 确认弹窗 → pluginApi → request（Cookie + 写操作 CSRF 标记）→ AdminPluginController（所有者校验）→ PluginApiService → PluginManager → Runtime/健康验证/Registry → 状态响应 → 反馈 → 重新查询列表。
登记走 ManifestReader；启用走受信任 BackendBinding 的 factory 和健康计划。前端不提供任意执行命令。
禁用返回 202 → 显示停止中 → 用户稍后“继续清理” → 再次 POST disable → 返回 stopped=true → 才允许卸载。GET 刷新不会代替资源释放。

## 4. 设计理由
服务端是状态的权威来源。操作不做乐观成功、不自动重试；成功和失败后都刷新列表，因为断网不代表服务端没有执行。
运行模式必须由用户从 declaredModes 与 configuredModes 的交集中选择。声明支持 Docker 不等于服务器已经配置 Docker 后端。
公共 request 默认仍只接受业务码 200，插件登记显式接受 201，禁用显式接受 200/202。

## 5. 替代设计
1. 点击后立即乐观切换为启用：启动和健康验证可能失败，界面会错误承诺可用，所以等待真实响应。
2. 前端轮询 GET 并把 202 当最终成功：当前后端需要后续 disable 调用继续释放资源，单纯轮询无法完成清理，所以提供明确操作。
3. 在浏览器 parse/stringify Manifest：会合并重复字段，绕过严格解析器对错误说明书的提示，所以原文发送。

## 6. 工程风险
登记和状态只存在于单个服务进程，重启丢失；当前不支持集群一致性或持久安装。
真实 PaddleOCR 和 Docker 启动适配器尚未提供，configuredModes 为空时页面禁止启用。
状态可能被其他会话改变；按钮只改善交互，后端仍必须检查权限、身份和状态。
健康检查与启动可能较慢；离开页面不取消后台操作，需要重新读取状态。
当前使用手动刷新，无后台状态推送。没有声称完成真实 OCR 联调。

## 7. 关键代码逐段解释
### pluginApi.ts
View 类型保留三个状态字段，避免把 ENABLED 推导为健康。路径 ID 经 encodeURIComponent 编码。install 直接把原文作为 body；disable 检查响应 stopped，而非只看请求是否成功。
### PluginsPage.tsx
外层先检查 deploymentOwner，未授权不挂载查询组件。useQuery 负责列表；useMutation 按 action 调用 API，默认禁止重试。同步 ref guard 阻止同一次操作重复提交；onSettled 等待刷新后解除。没有先修改本地插件状态。
### PluginItem.tsx
stopped 只在无 Runtime 或 STOPPED 时成立。启用要求管理禁用、已停止且存在配置；卸载要求禁用并已停止。STOPPING/STOP_FAILED 显示继续清理。ISOLATED 禁止直接健康恢复，要求先清理再启用。
### ManifestDialog.tsx
Blob.size 按 UTF-8 字节判断大小，不能用字符串长度替代。文件读取失败会阻止提交旧内容。mutationFn 包装调用只传 raw，避免把 React Query 的上下文误传给 API。失败保留输入并刷新列表。
### PluginActionDialog.tsx
模式初值为空，要求用户主动选择。pending 时不能重复确认或关闭弹窗。错误提示要求核对刷新后的状态。共用 Radix Dialog 处理语义、焦点和 Escape。
### App.tsx / AdminLayout.tsx / AdminPage.tsx
注册懒加载路由和所有者导航。仅插件路径允许部署所有者进入；其他管理页保留原权限规则。AdminPage 可替换提示，插件页明确内存限制，不沿用持久化承诺。窄屏顶栏对长标题省略并压缩图标间距。

## 8. 理解检查问题
1. 为什么 HTTP 202 成功响应仍不能让用户卸载插件？
2. 为什么前端禁止启用仍不能代替后端权限与状态检查？
3. declaredModes 包含 Docker，但 configuredModes 为空，说明什么？
4. 登记请求报网络错误后，为什么应该刷新列表而不是直接重复提交？
5. 为什么原文提交比 parse/stringify 更适合严格 Manifest 校验？

## 验证范围
前端全量 Vitest 87 项；生产构建；后端定向 212 项（SPI 54、core 139、server 19）。
浏览器使用隔离 API 响应验证管理操作，不连接生产数据，不等于真实 PaddleOCR 或完整后端 E2E。
正确性复查覆盖成功码、停止中/清理完成、模式选择、原始 JSON、错误后刷新。
安全性复查覆盖所有者门禁、现有 Session/CSRF、文本渲染、禁止任意命令、无乐观发布。

浏览器复验：登记→启用→健康检查→202 停止中→继续清理→卸载通过；320/768/1024/1440 宽度稳定后无横向溢出；Tab 焦点与 Escape、非所有者不查询且隐藏入口通过。修正测试路由脚本后最终控制台无错误或警告。
