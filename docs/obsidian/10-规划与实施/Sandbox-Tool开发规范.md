# Sandbox Tool 开发规范

Tool 必须具有稳定名称/版本、不可变镜像 digest、固定 entrypoint、输入/输出 JSON Schema、权限、风险、幂等、取消、超时、资源、网络和文件限制。容器从 `/sandbox/request/request.json` 读取参数，只能向 `/sandbox/output/result.json` 写入一个 JSON 结果；标准输出仅作受限诊断。

禁止 Shell 拼接、动态加载用户模块、运行时安装依赖、持有平台长期凭据、访问 MinIO/数据库/MQ、写输入目录、创建符号链接或后台进程。每个 Tool 必须提供正常、非法输入、超时、资源超限、取消、路径攻击和脱敏测试，并记录镜像来源、许可证、SBOM 与扫描结果。

