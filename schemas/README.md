# 生成的 YLcloud Schema 快照

本目录中的同名 Workflow Schema 不是权威源。权威契约位于 YLcloud 仓库的 `schemas/`。
`service-jwt-claims.schema.json` 同样只允许从权威目录同步。

同步：

```powershell
python scripts/check_ylcloud_schemas.py --source D:\path\to\ylcloud\schemas --sync
```

检查：

```powershell
python scripts/check_ylcloud_schemas.py --source D:\path\to\ylcloud\schemas
```

禁止直接修改生成快照；任何契约变更必须先在 YLcloud 仓库完成并通过双端契约测试。
