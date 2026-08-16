# Sandbox 运维手册

生产 Runner 只部署在独立 Linux 节点，优先 Rootless Docker；镜像按 digest 预拉取并完成漏洞扫描、SBOM 和签名验证。上线前检查 cgroup v2、seccomp、AppArmor/SELinux、磁盘水位、时间同步与清理任务。告警覆盖队列深度、启动失败、超时/OOM、安全拒绝、孤儿容器、清理失败和磁盘水位。

紧急处置：关闭接单 Feature Flag；取消运行任务；撤销短时凭据；清理租约过期容器与目录；保留父 Trace 和安全摘要；禁止改为宿主机运行。备份只包含父 Trace/任务元数据和已提交输出，不备份临时目录。

