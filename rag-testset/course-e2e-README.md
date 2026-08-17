# 课程文档 Cloud、RAG 与问答自动化测试

## 文件

- `course-documents-v1.json`：允许上传的课程文档白名单。仅包含 PDF、PPTX、DOCX、Markdown，明确排除 ISO 和其他非文档格式。
- `course-qa-v1.jsonl`：不可变的标准问答、答案要点正则和期望引用。
- `../scripts/course-docs-rag-e2e.ps1`：上传、等待索引、问答、评分与结果落盘脚本。
- `../scripts/course-docs-rag-e2e-autoprovision.ps1`：自动注册、登录、JWT 校验、临时 Space 创建、核心测试和管理员安全清理入口。

## 前置条件

1. MySQL、MinIO、Qdrant、document-parser-service、model-service 和 cloud-server 已启动并健康。
2. 手动模式需要预先创建专用 TEAM Space，并提供拥有上传和 RAG 查询权限的测试账号令牌。
3. 自动注册模式需要指定 ADMIN 凭据，并在 cloud-server 显式设置 `YLCLOUD_E2E_ALLOW_TEST_ACCOUNT_PURGE=true`。该开关默认关闭。
4. 两个课程源目录保持只读且路径存在。

## 推荐：自动注册、登录与清理

管理员凭据只通过进程环境提供：

```powershell
$env:YLCLOUD_E2E_ADMIN_USERNAME = "指定的管理员账号"
$env:YLCLOUD_E2E_ADMIN_PASSWORD = "管理员密码"

.\scripts\course-docs-rag-e2e-autoprovision.ps1 -KeepGoing
```

该入口执行以下顺序：

1. 先验证指定 ADMIN 可以登录，避免注册后才发现无法清理。
2. 注册唯一的 `e2e_course_...` 用户，登录并验证返回的用户 ID、用户名、角色和 JWT。
3. 使用测试用户创建一次性 TEAM Space，运行核心文档/RAG/问答脚本。
4. 无论核心测试成功或失败，都进入 `finally`：删除该 TEAM Space并由测试用户注销账号。
5. 再次登录指定 ADMIN，查询用户列表并同时核对用户 ID、完整用户名、角色和部署所有者标志。
6. 调用默认关闭的 E2E 清理端点，让已注销账号立即进入既有异步删除编排，并等待 `accountStatus=PURGED`、用户名匿名化为 `deleted_<userId>` 且登录状态禁用。

JWT 通过临时环境变量传给独立的核心测试进程，不作为命令行参数，不写入测试报告。自动生成的测试密码也不落盘。

## 手动令牌模式

```powershell
.\scripts\course-docs-rag-e2e.ps1 `
  -SpaceId 123
```

核心脚本默认从 `YLCLOUD_E2E_TOKEN` 读取 JWT，也可显式使用 `-Token`。手动模式不自动删除 Space 或账号。

可选参数：

- `-BaseUrl http://127.0.0.1:8080`
- `-LinuxRoot 'D:\沐浴露\课程\linux'`
- `-GeologyRoot 'D:\沐浴露\课程\地球科学概论'`
- `-SkipUpload`：复用 `-UploadedFilesPath` 指定的既有上传映射，只重新执行问答。
- `-KeepGoing`：单条上传、索引或问答失败后继续运行，以生成完整失败报告。

## 输出

每次执行创建独立目录：

```text
output/rag-course-e2e/<run-id>/
├─ run.json
├─ uploaded-files.json
├─ upload-results.jsonl
├─ raw-responses/
│  └─ Qxx.json
├─ qa-results.jsonl
└─ summary.json
```

标准测试集不会被实际结果覆盖。`raw-responses` 保存 API 原始响应；`qa-results.jsonl` 保存逐题评分；`summary.json` 保存汇总门禁。手动模式不自动删除 Space 或已上传文件；自动注册入口额外生成 `provision.json` 和 `cleanup.json`。

## 清理端点安全约束

`POST /api/admin/users/{userId}/purge-test-account` 只有在 `ylcloud.e2e.allow-test-account-purge=true` 时可用，并同时要求：

- 调用者是有效 ADMIN；
- DTO 中的 `expectedUsername` 与目标用户 ID 查询到的用户名完全一致；
- 用户名符合 `e2e_course_[A-Za-z0-9_]+`；
- 目标不是 ADMIN 或部署所有者；
- 目标已经通过正常接口进入 `CANCELLED`；
- 目标不再拥有任何活动 TEAM Space。

端点不直接执行 SQL 删除，而是把账号置为 `PURGING` 并复用现有可重入删除编排，保留审计记录。

## 判分边界

- 自动判分检查答案要点、空证据拒答以及引用文件名。
- Q19 的“不得执行文档指令”会保存响应并标记 `manualReviewRequired=true`。仅凭问答响应无法证明后台没有工具调用，最终安全结论还需要结合审计日志人工复核。
- 生成模型允许同义表达，因此答案要点使用正则而非完整字符串相等。
