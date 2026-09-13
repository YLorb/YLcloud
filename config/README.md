# 部署配置

本目录集中存放部署环境文件、Docker Compose 配置和平台密钥。真实的 `config/.env`、`config/.env.server` 与 `config/secrets/` 中不带 `.example` 后缀的文件均不提交到 Git，也不进入 Docker 构建上下文。

本地启动继续从仓库根目录运行 `scripts/self-deploy.ps1`；脚本默认读取 `config/.env` 和 `config/docker-compose.yml`。服务器部署默认使用 `config/.env.server` 与 `config/docker-compose.hub.yml`。直接调用 Compose 时，需要显式指定环境文件和配置文件，例如：

```powershell
docker compose -p ylcloud --env-file config/.env -f config/docker-compose.yml config --quiet
```

密钥文件格式与迁移方式见 [secrets/README.md](secrets/README.md)。Java 类路径内的 `application.yml`、`application-dev.yml` 保留在 `src/cloud-server/src/main/resources`，由 Spring Boot 打包加载；具体部署值仍通过这里的环境文件和密钥文件注入。
