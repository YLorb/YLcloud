# Phase 7：插件管理 HTTP API

已接入 cloud-server，阶段完成后停止。代码在 add/Plugin 工作树，未提交/推送。
本阶段的安装仅登记 Manifest：不上传二进制包、不下载、解压或启动任意脚本，记录仅保存在进程内。
真实 PaddleOCR、Docker、本地 Python 适配和持久化仍未实现；未提供管理前端页面。

## 1. 业务目标

把前六阶段的进程内能力提供给部署所有者：登记、查询、受健康验证保护的启用、禁用排空、卸载及健康复查。
对外区分登记成功、启动成功、健康通过、停止中及资源释放成功，避免用一个 success 隐藏真实状态。

## 2. 核心抽象与职责

| 抽象 | 职责 |
| --- | --- |
| AdminPluginController | 路由、部署所有者权限、HTTP 状态及 Result 响应 |
| PluginApiService | 用例编排、可信后端选择、状态视图、安全错误映射 |
| PluginBackendBinding | 服务端按插件 ID + 插件包 version + runtime mode 绑定工厂、限额与健康计划 |
| PluginApiConfiguration | 装配共享 Registry/Manager/Service，允许没有任何运行后端的服务启动 |
| View | 提供插件 ID/名称/版本、Provider IDs、声明与已配置模式、三种状态、revision、安装与持久化语义 |
| DisableResult.stopped | 是否完成排空和资源关闭；false 必须返回 HTTP 202 |
| enableIfInstalled | 后端选择后核对同一份安装声明，拒绝期间卸载重装后的旧请求 |
| awaitStoppedIfInstalled | 核对安装声明和 runtimeId，只等待指定运行，不误停新的 Runtime |

API 每次从原始 JSON 读取都会创建新的不可变 Manifest；本阶段 API 用该对象身份标记后端选择时看到的安装。
Core 内启用仍有 Entry 身份与 revision 检查；受保护的停止还检查 Runtime UUID。
宿主直接复用相同 Manifest 对象重复 install 不属于此 API 标记方案的使用方式。

## 3. 完整调用链

写请求 → BrowserCsrfInterceptor → MaintenanceInterceptor → SessionInterceptor → UserPermissionInterceptor
→ AdminPluginController.requireDeploymentOwner → PluginApiService → PluginManager → Registry / Runtime。
生产 WebConfig 已覆盖 /api/admin/plugins，没有新增权限或 CSRF 豁免。

安装：部署所有者校验 → 直接读取 Servlet InputStream → 严格 ManifestReader（64 KiB 上限）
→ Manager.install → 201，installationKind=MANIFEST_REGISTRATION，persistence=PROCESS_MEMORY。
输入流由 Servlet 容器拥有，Reader 不主动关闭它。

启用：根据已安装插件的 ID/version/mode 选择服务端绑定 → 核对仍是同一次安装
→ 启动 Runtime → 全包样例验证 → Entry/revision 复核 → 整批发布 → 200。
后端/健康样例未配置时返回 503，且不会创建 Runtime。客户端不能提交 factory、类名、命令或断言。

禁用：关闭入口 → 撤销登记 → 保存本次安装与运行身份
→ awaitStoppedIfInstalled 按等待预算排空并清理
→ 再核对身份和状态 → 已完成返回 200，否则返回 202 与 stopped=false。
没有后台自动清理任务；收到 202 后查询状态，并再次 POST disable 继续清理，GET 本身不释放资源。

卸载：Manager 确认 DISABLED 且没有未停止的 Runtime → 删除登记 → 200。
健康复查：仅使用当前服务端绑定的健康计划 → Manager.checkHealth → 200 或 422。
关闭应用时 Service 尽力禁用并等待每个 Runtime；未完成/失败只记录固定安全提示，不能保证强制回收本地代码。

## 4. 为什么采用当前设计

沿用仓库 Session、CSRF、部署所有者权限和 Result，不建立第二套管理身份。
插件执行影响整个部署，所以普通管理员角色不自动获得管理权，必须 deploymentOwner=true。

安装直接读取有界原始流，保留 Phase 3 对重复 JSON 字段、未知字段、尾随内容的严格检查，不能先反序列化后丢失重复字段证据。
返回三种状态，避免把 ENABLED 误当健康，或把 DISABLED 误当资源已关闭。
后端绑定只来自服务端 Spring Bean，把可执行配置与客户端声明分开。

## 5. 替代设计

1. 上传 ZIP 后动态加载/执行：更接近完整市场安装，但需要文件安全、签名、持久化、依赖和部署适配。本阶段严格使用已有 Manifest 登记能力。
2. 所有操作异步化并返回任务 ID：适合很慢的部署，但需要队列、任务持久化和重启恢复。本阶段同步启用/检查，禁用明确报告停止中，后端自行限制 I/O。
3. 所有结果都返回 HTTP 200：客户端实现简单，但错误和停止中容易被误当完成。使用 201、202、4xx、503 等真实 HTTP 状态，并让 Result.code 保持一致。
4. 允许客户端指定镜像启动命令或实现类：灵活但扩大执行权限边界。当前只允许选择 Manifest 声明且服务端绑定的 LOCAL/DOCKER。

## 6. 工程风险与边界

- 内存登记，无数据库迁移、重启恢复、多节点一致性、二进制包安装或版本升级。
- 启用和健康复查为同步 HTTP 调用，可能受反向代理超时影响。Factory.start、宿主输入流和 Session.close 必须有自身 I/O 超时。
- waitMillis 只控制 Runtime 的排空等待，不是 Session.close 或整个 HTTP 请求的强制总时限。
- 202 不表示后台有自动运行的清理任务；调用方需再次 POST disable，直到 stopped=true。重试失败时保留 Runtime 供诊断和后续清理。
- 重复安装/重复启用返回 409，不自动创建第二个实例；重复禁用可以继续清理；重复卸载返回 404。尚无请求幂等键或跨重启幂等保证。
- 并发状态可能在响应后立即变化。视图是观察快照，runtimeState 与 health 来自独立观察，客户端不能把它当跨线程事务或授权凭据。
- 收到健康/启动/清理错误后应 GET 查询实际状态，不能假定所有资源已释放。查询不会执行自动修复。
- 同一包的后端绑定必须匹配 ID、包 version 与模式；声明与实例的 Provider version 仍由 core 单独核对。
- API 没有暴露 enableUnverified、裸 Provider、健康断言或 parse 执行接口。
- 服务启动时允许后端绑定为空，当前仓库没有生产 PaddleOCR 绑定，因此示例登记成功后启用仍会返回 503。
- 未新增操作历史持久化。权限校验沿用已有部署所有者审计行为，不应将其等同于完整插件操作审计。
- 未对真实数据库登录、浏览器页面、容器部署或 OCR 精度进行 E2E 验证。

## 7. 关键代码逐段解释

AdminPluginController：每个端点先调用 requireDeploymentOwner，再进入服务层。安装端点不绑定普通 JSON DTO，而是把原始流交给严格 Reader。

```java
var binding = bindings.get(new Key(id, manifest.version(), mode));
```

模式或包版本不匹配时没有运行后端，不能把其他版本的工厂套上去，更不能自动回退到另一个实现。

```java
manager.enableIfInstalled(id, mode, binding.factory(), binding.limits(), binding.healthPlan(), manifest);
```

Manager 在启动前核对声明对象身份，避免后端选择期间同 ID 重装使旧配置作用于新安装；随后仍执行原有 Entry/revision 防护。

```java
manager.awaitStoppedIfInstalled(id, budget, disabled.manifest(), runtimeId);
```

等待前确认还是指定 Runtime。等待结束后服务再核对身份和禁用状态，避免旧请求把新运行的状态报告为 stopped=true。

```java
return response(result.stopped() ? 200 : 202, result);
```

停止中不是错误，但也不是完成。响应中的 Result.code 与实际 HTTP 状态一致。

异常映射不透传插件异常正文或 cause：Manifest 400/413，管理/注册冲突 409，不存在 404，健康失败 422，运行不可用 503，其他失败 500。
BaseException 使用仓库现有 GlobalExceptionHandler 生成统一 JSON；插件原因链不会交给该 handler 无筛选输出日志。

## 8. 理解检查问题

1. 安装接口返回 201，为什么还不能认为 PaddleOCR 已部署可用？
2. disable 返回 202、stopped=false 时，前端应该显示什么，并如何继续清理？
3. 为什么后端绑定既要匹配插件 ID，还要匹配包版本与部署方式？
4. 等待旧 Runtime 停止前为什么要核对 runtimeId，不能只使用插件 ID？
5. 为什么客户端不能提交健康断言或任意启动命令？

## HTTP 契约

所有路径前缀 `/api/admin/plugins`，均要求已登录的部署所有者。
写请求要求 `X-YLCloud-Request: 1`；若有 Sec-Fetch-Site，必须满足现有 same-origin/none 规则。使用站点现有会话 Cookie，不接受该接口的 API Key 替代鉴权。

| 方法和路径 | 输入 | 成功响应 |
| --- | --- | --- |
| POST /api/admin/plugins | Content-Type application/json，原始 Manifest | 201 + View |
| GET /api/admin/plugins | 无 | 200 + View 数组 |
| GET /api/admin/plugins/{id} | 路径 ID | 200 + View |
| POST /api/admin/plugins/{id}/enable?mode=LOCAL | 查询参数 LOCAL/DOCKER，区分大小写 | 200 + View |
| POST /api/admin/plugins/{id}/disable?waitMillis=1000 | 查询参数 0～5000，默认 0 | 200 或 202 + DisableResult |
| DELETE /api/admin/plugins/{id} | 必须先禁用并完成释放 | 200，data=null |
| POST /api/admin/plugins/{id}/health-check | 无，由服务端选健康计划 | 200 + View |

只登记的 View 示例（后端尚未配置）：

```json
{
  "code": 201,
  "message": "success",
  "data": {
    "id": "example.paddleocr",
    "name": "PaddleOCR 文档解析（契约示例）",
    "version": "0.1.0-example",
    "providerIds": ["example.paddleocr.document-parser"],
    "declaredModes": ["LOCAL", "DOCKER"],
    "configuredModes": [],
    "managementState": "DISABLED",
    "selectedMode": null,
    "revision": 0,
    "runtimeState": null,
    "health": null,
    "installationKind": "MANIFEST_REGISTRATION",
    "persistence": "PROCESS_MEMORY"
  }
}
```

declaredModes/configuredModes 是集合，响应数组顺序没有语义保证。configuredModes 只表示存在后端绑定，不证明健康验证通过。
View 是状态视图，不是可直接重新提交的 Manifest；登记输入使用 schemas 下的原始 Manifest 格式。
错误响应沿用 `{ "code": 409, "message": "...", "data": null }`。

## 验证

命令：

```powershell
mvn -o -pl src/cloud-server -am test '-Dtest=AdminPluginControllerTest,Plugin*Test,ProviderRegistry*Test,Document*Test,SessionSecurityTest,HybridDocumentParserTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

最终 212 个测试通过：SPI 54、core 139、server 定向 19。server 中新增 API 测试 12、Spring 配置测试 1，既有 SessionSecurityTest 4、HybridDocumentParserTest 2。
API 使用真实 MockMvc 路由、真实 Session/CSRF 拦截器与 AdminPermissionService，登录会话和用户数据库依赖是替身，运行后端也使用测试替身。
Spring ApplicationContextRunner 验证无后端绑定时配置能装配；不等于完整生产应用及数据库启动验证。
首轮新增测试有 JSON 字符串转义编译错误，修正后全部通过。没有运行后端全量测试或浏览器 E2E。

正确性自查：路由与返回码、三种状态、202 清理语义、后端版本匹配、重复请求、并发身份及无后端装配。
安全性自查：所有端点部署所有者校验、Session/CSRF 路径覆盖、64 KiB 原始流解析、禁止动态命令、插件异常不透传、无生产数据或外部消息操作。

后续已添加管理前端，见 [FRONTEND-INTEGRATION.md](FRONTEND-INTEGRATION.md)；上述无浏览器验证仅指 Phase 7 API 交付时的验证范围。
