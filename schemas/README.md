# YLcloud 跨仓库契约

本目录是 YLcloud 与 `workflow-service` 之间 JSON Schema 的唯一权威来源。

- `workflow.schema.json`：兼容现有 Workflow v1。
- `workflow-v2.schema.json`：兼容现有 Graph Workflow v2。
- `workflow-run-contracts.schema.json`：Run API、Result、Callback、Tool、Snapshot、Trace 和状态契约。
- `service-jwt-claims.schema.json`：四个服务 audience、scope 与资源绑定 Claims 契约。
- `service-jwt-claims.schema.json`：四个服务 audience、scope 与资源绑定 Claims 契约。
- `examples/`：Python 与 Java 契约测试共用的规范样例。

`new_project/schemas` 只能保存由本目录同步得到的生成快照。修改契约时必须先改本目录，再运行 `new_project/scripts/check_ylcloud_schemas.py` 的同步或检查模式；禁止在消费仓库独立修改同名 Schema。

兼容策略：当前 API `contractVersion` 为 `1.0`，Workflow IR 同时保留 `1.0` 与 `2.0` 读取兼容。新增可选字段属于向后兼容；删除字段、修改既有字段含义或收紧既有取值范围必须发布新的契约版本。
