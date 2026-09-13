# Workflow MySQL 运行库

Workflow 只连接独立的 `ylcloud_workflow` Schema。应用账号不得拥有 YLcloud 业务 Schema 的权限，也不得拥有 `FILE`、`PROCESS`、`SUPER`、`SHUTDOWN`、`CREATE USER` 等全局权限。

初始化账号由数据库管理员执行，密码通过部署 Secret 注入，不写入仓库或 Compose 明文配置：

```sql
CREATE DATABASE IF NOT EXISTS ylcloud_workflow
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'workflow'@'%' IDENTIFIED BY '<secret-from-deployment-store>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON ylcloud_workflow.* TO 'workflow'@'%';
```

运行时设置 `WORKFLOW_MYSQL_ENABLED=true`，并通过 `WORKFLOW_MYSQL_HOST`、`WORKFLOW_MYSQL_PORT`、`WORKFLOW_MYSQL_USER`、`WORKFLOW_MYSQL_PASSWORD`、`WORKFLOW_MYSQL_DATABASE` 注入连接信息。服务启动时只执行向前 migration；已应用脚本的 SHA-256 发生变化或数据库版本高于代码时，启动失败并保持 `/ready` 为不可用。

`POST /internal/v1/workflow-runs` 只有在 Run、首个 Execution 和幂等记录同一事务提交后才返回 202。调度所有权由数据库租约和版本 CAS 决定，进程重启后重新扫描 `QUEUED` 及租约过期的运行。终态结果在 Java 返回精确 `resultHash` ACK 前保存在临时结果表，ACK 后幂等删除。
