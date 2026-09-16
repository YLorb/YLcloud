# Docker Hub 镜像发布

工作流：`workflows/docker-publish.yml`。原 ACR 镜像同步工作流已移除，发布流程不再需要 ACR 凭据。
实现采用 [Docker 官方 GitHub Actions](https://docs.docker.com/build/ci/github-actions/)。

## GitHub 仓库配置

在 Settings → Secrets and variables → Actions 配置：

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Variable | `DOCKERHUB_USERNAME` | Docker Hub 登录用户名，必填 |
| Secret | `DOCKERHUB_TOKEN` | 具备目标仓库写权限的 Docker Hub Access Token，必填 |
| Variable | `DOCKERHUB_NAMESPACE` | 可选，组织或个人命名空间；默认使用登录用户名 |

确保账号有权推送目标命名空间下的仓库。不要将 Token 写入代码、构建参数或镜像。

## 触发与标签

- 推送 `dev`：发布 `dev` 和 `sha-<短提交号>`。
- 推送 `main`：发布 `latest` 和 `sha-<短提交号>`。
- 推送 `v*` 标签：发布原始标签和 SHA 标签；合法语义化版本还生成不带 `v` 的版本标签，例如 `v1.2.3` 同时生成 `1.2.3`。版本标签不会更新 `latest`。
- 针对 `dev`、`main` 的 Pull Request：只构建，不登录、不推送；未设置 Docker Hub 变量时使用 `ylcloud-ci` 作为本地构建标签命名空间。
- Actions 页面手动运行：发布所选 ref 的 SHA 标签，并按上述分支或版本规则添加标签。

每个镜像独立构建，平台为 `linux/amd64`，使用独立的 GitHub Actions 构建缓存。
发布不是跨镜像原子操作：必须确认全部任务成功，才能将该批次视为可部署版本。
工作流验证的是镜像构建，不替代回归测试。

## 发布镜像

镜像地址为 `docker.io/<命名空间>/<镜像名>:<标签>`：

- `ylcloud-app`
- `ylcloud-frontend`
- `ylcloud-model-service`
- `ylcloud-document-parser-service`
- `ylcloud-workflow-service`
- `ylcloud-sandbox-service`
- `ylcloud-tool-file-preflight`
- `ylcloud-tool-json-echo`

MySQL、MinIO、Qdrant 和 RabbitMQ 不重新打包或镜像转存。

## 部署边界

本次仅替换 CI 发布流程，不修改部署环境、密钥或正在运行的容器。
现有 `config/docker-compose.hub.yml` 实际仍使用 ACR 地址，不能直接作为这批 Docker Hub 镜像的部署清单；需要另行适配镜像地址。
Sandbox 工具目录中的镜像名也需按实际发布地址和版本配置，不会因 CI 发布而自动切换。
