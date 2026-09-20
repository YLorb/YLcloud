---
id: TEST-20260920-002
type: test-results
status: pending-verification
created: 2026-09-20
updated: 2026-09-20
tags: [ylcloud, share-link, testing]
---

# Share-link 实施与测试结果

## 目标与交付范围

验证 [[10-规划与实施/REQ-20260920-001-share-link需求]] 的真实 API、个人与空间分享、密码与下载额度、ZIP、生命周期、管理员日志、旧入口退役。代码已落地 `feat/share-link` 工作区；本次未提交、推送或部署生产环境。状态保留 pending-verification，表示下述未执行的完整发布验收仍需补充，不表示所有用例已通过。

## 环境、前置条件与数据

- Windows、Java 17、项目现有 Maven/React/Vite/Vitest；未升级依赖。
- 独立 Docker MySQL 8.4（33316）、MinIO（19090，开启版本）、Qdrant 1.15.4（16333/16334）；Spring Boot 18080、Vite 15173、Chromium。
- 使用专用 `share_test` 和升级演练库 `share_test_upgrade`，不接日常开发或生产数据库。集成测试会删除此专用库中的测试文件记录，禁止指向真实数据。
- 数据包含个人文件、空间空文件夹、中文子文件、替换对象版本、未知码和 31 天前访问日志；实际浏览器注册管理员与普通用户，上传 34 字节文件。
- 大文件采用生成流、MinIO 上传和 ZIP 输出到空接收器，不把全部文件放入测试堆；128 MiB 重复字节属于可压缩数据，不代表真实不可压缩文件吞吐。

## 用例、步骤与实际结果

每行预期结果也是通过标准。自动化完整步骤在对应测试方法，浏览器完整步骤在 `output/share-link-browser-*.js`。

| 用例 / 验收组 | 步骤 | 预期结果 | 实际结果及证据 |
|---|---|---|---|
| DB-01 / V01、V13 | 空库执行所有 Flyway；另库停在 V54，插入旧分享，再执行 V55/V56；重复迁移；恢复归档数据后请求旧接口 | 新表可用、旧表为零、归档保留；重复迁移安全；恢复旧记录不恢复公开能力 | 通过，`ShareMigrationTest`；`output/share-link-migration.log`，57 个迁移验证 |
| CODE-01 / V02 | 小/长 ID 编码、拼接截断补位；查找大小写不同码；制造唯一索引冲突 | 固定 8 位、大小写敏感；冲突回滚且不返回已有链接 | 通过，`ShareCryptoTest`、`caseSensitiveUniqueCodeAndCollisionRollback` |
| LINK-01 / V03、V04 | 同人创建、4 个并发创建；批准后修改限额；两次增加 7 天；过期后延长 | 复用同 ID、原地址保留、计数保留、原时间累加 | 通过，`reuseAndMutationKeepAddressAndCounts`、`expiryExtensionAndConcurrentCreatePreserveOneRecord` |
| LIFE-01 / V05 | 删除再恢复；UUID 替换再改回；同 UUID 创建新存储版本；收回权限再恢复 | 删除和权限可恢复；替换永久失效 | 通过，生命周期与版本测试；授权边界集成使用真实数据库和权限服务 mock，真实普通用户拒绝路径另由浏览器验证 |
| AUTH-01 / V05、V11 | 普通用户访问 admin 日志、修改其他人链接、为其他人文件创建；匿名请求管理列表 | 分别为 403、403、403、401 | 通过，`output/share-link-browser-edit-security.js`，真实登录与 API |
| PASS-01 / V06 | 长 Unicode 密码哈希；错误密码；正确验证后更新密码；刷新页面 | 哈希带盐、凭据签名隔离；错误拒绝、旧凭据失效、重开需密码；互斥条件拒绝 | 通过，后端单元/集成、前端测试和匿名浏览器 |
| QUOTA-01 / V07 | 剩余 1 次启动 8 个并发批准；同人正常批准 | 恰好 1 次成功，数据库计数 1 | 通过，`lastDownloadCannotBeApprovedTwiceConcurrently` |
| QUOTA-02 / V04、V07、V09 | 并发修改条件和批准；再次提交旧版本；模拟输出流断连并关闭下载上下文 | 不死锁、不丢次数、旧版本拒绝；断流不退次数、删除临时清单 | 修复统一 source → link 加锁顺序后通过，`concurrentConditionEditAndApprovalPreserveCountAndVersion`、`interruptedTransferKeepsApprovedCountAndDeletesManifest` |
| ZIP-01 / V08、V09 | 创建目录链接；新增中文文件、替换原子文件；批准输出 ZIP 并读回 | 新文件存在、替换文件排除、内容准确、ZIP 总计 1 次 | 通过，`zipIsDynamicAndExcludesReplacedChildren`；空间空目录浏览器下载为有效 22 字节 ZIP |
| VERSION-01 / V07、V09 | 批准下载，再覆盖同 UUID 存储对象并更新版本，最后传输 | 已批准请求读取批准时的原版本 | 通过，`alreadyApprovedDownloadKeepsOriginalObjectVersion` |
| CAP-01 / V09 | 96 MiB 最大 JVM 堆；生成并上传 128 MiB；批准 ZIP、输出空接收器 | 不随总字节分配全量内存；完成传输、清单小于 4 KiB、计数 1 | 通过，`output/share-link-final-integration.log` 的 `SHARE_PERF`；首次最终资源验证 1,932 ms，重跑数值以日志为准 |
| LOG-01 / V11、V12 | 打开未知码、登录/匿名链接；插入 31 天前日志；执行清理 | 记录访问、城市“-”；只删过期日志，计数不重置 | 通过，`logsIncludeUnknownCodesAndRetentionDoesNotResetCounts`；浏览器断言管理员日志含真实用户名 |
| UI-01 / V10 | 个人文件设置密码/限额，创建、复制；匿名开卡片、验证并下载；刷新；修改、撤销 | 当前 origin 短链接；无密码验证不泄露文件名；下载一致；刷新重输；撤销过期 | 通过，下载文件与原文件 SHA256 一致；`share-link-dialog.png`、`share-link-public.png`、`share-link-expired-mobile.png` |
| UI-02 / V08、V10 | 空间文件夹强制下载 1 次；重开；编辑延长 7 天、额度改 2；390px 视口检查 | 无目录预览/文件卡片；下载后失效；编辑保留原码和次数；无水平溢出 | 通过，`share-link-browser-space.js`、`share-link-browser-edit-security.js`、`share-link-edit-mobile.png` |
| OLD-01 / V10、V13 | 请求旧 `/share` 页面和旧 API 的 preview/stream/download/query 变体 | HTTP 200、“文件已过期”、无文件内容 | 通过，旧控制器迁移测试及浏览器请求；旧创建接口停止创建 |

## 回归与复现命令

仓库根目录运行 Java；前端目录运行 npm。测试数据库变量仅指向上面的专用实例。

```powershell
$env:SHARE_TEST_DB='jdbc:mysql://127.0.0.1:33316/share_test?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
mvn -q -pl src/cloud-server -am test
mvn -q -pl src/cloud-server -am '-Dtest=ShareCryptoTest,ShareLinkIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx96m' '-Dshare.test.bytes=134217728' test
mvn -q -pl src/cloud-server -am '-Dtest=ShareMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- 完整 Java 回归曾通过 357 项（后续新增的分享用例再作专项验证，不能把多轮 XML 汇总说成一次完整回归）。日志 `output/share-link-maven.log`，最终专项看 `output/share-link-final-integration.log` 与 Surefire XML。
- 最终分享专项 19 项通过：15 项真实 MySQL/MinIO 集成、4 项单元；失败/错误/跳过均为 0。最终 128 MiB/96 MiB 堆资源测试为 2,312 ms。
- 前端 `npm run test -- --run`：10 个文件、62 项通过；`npm run build` 通过。
- Maven package 通过并启动真实服务，浏览器依据该版本测试。后续 ZIP 资源释放/路径处理小修再次执行 Java 专项测试，浏览器未重复全链路。
- 浏览器截图与测试驱动、日志位于仓库 `output/`，属于本地证据，尚未提交。管理员最后一张截图未稳定捕捉弹窗，因此账号日志结论取自浏览器断言，不能依据那张截图宣称弹窗视觉验收通过。

## 已修复问题与未执行项

- 清理其他分支残留的 target 迁移文件后，Flyway 正常；时间截断到 MySQL 微秒精度，消除纳秒舍入导致的新建链接立即误判替换。
- 固定批准时的 MinIO 版本；ZIP 结束/失败均释放压缩资源；路径去除分隔符、控制符和 Windows 驱动器冒号。
- 新增竞争测试复现条件更新与批准死锁，统一先锁源节点、再锁链接后通过；未引入自动重试。
- 浏览器初期后端未就绪有请求失败；自动化重复提交上传导致 409；有效期定位器改为 combobox role 后成功。这些不能作为零控制台错误的证据。
- 未执行：生产库规模迁移/备份恢复、生产网络与 HTTPS、所有目标浏览器、不可压缩大文件多用户持续负载、真实 MinIO 服务中断与复杂跨目录移动组合的系统性验收。保留 TASK-005、009 的完整发布验证待办。
- Flyway 现有版本对 MySQL 8.4 给出支持范围警告，实际本次迁移通过；没有借本功能升级依赖。
- 用户明确延期的碰撞重试、防重复请求、网关限流、外部 IP 库仍见 [[10-规划与实施/ISS-20260920-001-share-link后续改进]]。

## 发布与回滚说明

上线前备份数据库，随新后端执行 V55/V56，随后发布新前端。V56 自动把旧 `file_share` 复制到 `file_share_retired` 再清空；归档仅用于恢复与审计。新接口为 `/api/share-links`、`/api/public/share-links` 和管理员分享接口，公开页面 `/s/{code}`。

回滚优先保留数据库新结构、恢复 UI 或前滚修复；必须保留旧公开入口的失效控制器，不能直接回退到仍会提供旧分享下载的历史二进制。数据库归档恢复不等于恢复旧公开链接。
