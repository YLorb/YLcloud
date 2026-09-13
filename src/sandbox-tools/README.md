# Sandbox tools

每个目录都是平台审核的固定工具镜像。构建、扫描并推送后，使用 Registry 返回的 `sha256` digest 写入 `sandbox-service` Tool Catalog；禁止登记 tag 或 `latest`。`json-echo` 只用于端到端安全烟测，不作为业务工具。

