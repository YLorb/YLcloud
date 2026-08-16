# Sandbox 威胁模型

保护用户文件、平台密钥、业务网络、执行节点稳定性和 Trace 隐私。路径穿越/符号链接由可信 staging 校验；zip bomb 由数量、深度、倍率和空间配额限制；fork/OOM/磁盘耗尽由 cgroup、PID、临时盘和超时限制；SSRF 由默认断网阻断；逃逸风险由独立节点、Rootless、非 root、cap drop、seccomp/AppArmor 和镜像治理降低；跨用户访问由主体、父 Trace 与四阶段授权复核阻断；日志泄密由摘要和字段脱敏治理。

残余风险：普通 Docker 与宿主共享内核。未来若开放用户代码或匿名多租户执行，必须重新评审并迁移 gVisor/microVM。

