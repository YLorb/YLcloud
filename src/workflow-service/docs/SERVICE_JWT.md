# Service JWT 运维约束

服务 Token 只使用 HS256，最长 300 秒，允许时钟偏差最多 60 秒。四个 audience 固定为 `ylcloud-workflow`、`ylcloud-tool-gateway`、`ylcloud-model-service`、`ylcloud-workflow-callback`；Token 必须使用最小 scope，并按 Claims Schema 绑定已知的 user、session、assistant message、run、execution、node 和 invocation。

Secret 只能由部署 Secret 注入。Java、Workflow 和 model-service 不生成宿主机 Secret，不在日志、错误响应或审计 payload 中记录 Token/Secret。缺少 active secret 时内部业务端点 fail-closed，健康探针仍可用于诊断。

轮换步骤：

1. 运维在应用外生成新 Secret，将原 active 作为 previous，并设置 previous 的绝对 Unix 截止时间；截止窗口不得长于“最大 Token TTL + 最大时钟偏差”（当前上限 360 秒）。
2. 同一轮发布把新 active、旧 previous 和相同截止时间注入 Java、Workflow、model-service；先验证新 active，再在截止前兼容旧 Token。
3. 观察错 audience、签名失败、过期和超 scope 的脱敏计数，不记录原 Token。
4. 所有实例切换且截止时间到达后移除 previous；即使配置仍残留，代码也拒绝截止后的旧签名。

若轮换失败，回滚部署版本并恢复上一组 active 配置；不得通过延长 Token TTL、关闭 audience/scope 校验或允许匿名模型调用来恢复服务。JWKS 是后续迁移项。
