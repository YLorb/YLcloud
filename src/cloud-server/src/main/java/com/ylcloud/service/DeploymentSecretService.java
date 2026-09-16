package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.DeploymentSecretVO;
import com.ylcloud.context.BaseContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DeploymentSecretService {
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("llmApiKey", "llm_api_key", "LLM API Key", "主要聊天模型供应商的访问密钥", true, "下一次模型请求自动生效"),
            new Definition("arkApiKey", "ark_api_key", "Ark API Key", "Ark/OpenAI 兼容生成与 VLM 的回退密钥", true, "下一次模型请求自动生效"),
            new Definition("ragQueryApiKey", "rag_query_api_key", "RAG Query API Key", "查询生成专用密钥；未配置时回退到 Ark Key", true, "下一次模型请求自动生效"),
            new Definition("vlmApiKey", "vlm_api_key", "VLM API Key", "视觉模型专用密钥；未配置时回退到 Ark Key", true, "下一次模型请求自动生效"),
            new Definition("jwtSecret", "jwt_secret", "用户 JWT Secret", "启动级签名密钥，需要带过渡期的轮换流程", false, "需执行鉴权密钥轮换"),
            new Definition("serviceJwtSecret", "service_jwt_active_secret", "服务 JWT Secret", "服务间鉴权密钥，需要 active/previous 协调轮换", false, "需执行服务密钥轮换"),
            new Definition("rabbitmqPassword", "rabbitmq_password", "RabbitMQ 密码", "必须与 RabbitMQ 用户凭据同步更新", false, "需执行消息队列凭据轮换"),
            new Definition("sandboxServiceToken", "sandbox_service_token", "Sandbox 服务 Token", "Workflow 与 Sandbox 共用的服务鉴权 Token", false, "需协调重载两个相关服务"),
            new Definition("exportMasterKey", "export_master_key", "导出主密钥", "用于包装数据导出密钥，直接替换会影响历史导出", false, "需执行导出密钥迁移")
    );
    private static final Map<String, Definition> BY_KEY = DEFINITIONS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Definition::key, value -> value));

    private final Path directory;
    private final SecurityAuditService auditService;

    public DeploymentSecretService(
            @Value("${ylcloud.secret.write-directory:${YLCLOUD_SECRET_WRITE_DIRECTORY:config/secrets}}") String directory,
            SecurityAuditService auditService
    ) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.auditService = auditService;
    }

    public List<DeploymentSecretVO> list() {
        return DEFINITIONS.stream().map(this::status).toList();
    }

    public DeploymentSecretVO update(String key, String rawValue) {
        Definition definition = BY_KEY.get(key);
        if(definition == null) {
            throw new BaseException("未知 Secret：" + key);
        }
        if(!definition.editable()) {
            throw new BaseException("该 Secret 需要使用专用轮换流程：" + definition.label());
        }
        String value = rawValue == null ? "" : rawValue.trim();
        if(value.isEmpty()) {
            throw new BaseException("Secret 不能为空；如需停用功能，请关闭对应功能开关");
        }
        if(value.indexOf('\0') >= 0 || value.contains("\n") || value.contains("\r")) {
            throw new BaseException("Secret 必须是单行文本");
        }
        Path target = resolve(definition);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "." + definition.fileName() + ".", ".tmp");
            try {
                Files.writeString(temporary, value, StandardCharsets.UTF_8);
                applyOwnerOnlyPermissions(temporary);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch(AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                applyOwnerOnlyPermissions(target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch(IOException exception) {
            throw new BaseException("Secret 保存失败，请检查部署目录写权限");
        }
        auditService.recordCritical(new SecurityAuditService.AuditEventBuilder()
                .eventType("DEPLOYMENT_SECRET").action("UPDATE")
                .subject(BaseContext.getCurrentId(), null)
                .target("DEPLOYMENT_SECRET", definition.key(), definition.label())
                .result("SUCCESS"));
        return status(definition);
    }

    private DeploymentSecretVO status(Definition definition) {
        Path path = resolve(definition);
        boolean configured = false;
        LocalDateTime updateTime = null;
        try {
            configured = Files.isRegularFile(path) && !Files.readString(path, StandardCharsets.UTF_8).trim().isEmpty();
            if(Files.exists(path)) {
                updateTime = LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault());
            }
        } catch(IOException ignored) {
            // Status deliberately reveals neither file-system details nor secret contents.
        }
        return new DeploymentSecretVO(definition.key(), definition.label(), definition.description(), configured,
                definition.editable(), definition.activation(), updateTime);
    }

    private Path resolve(Definition definition) {
        Path path = directory.resolve(definition.fileName()).normalize();
        if(!path.getParent().equals(directory)) {
            throw new IllegalStateException("Secret path escaped configured directory");
        }
        return path;
    }

    private void applyOwnerOnlyPermissions(Path path) throws IOException {
        if(Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        }
    }

    private record Definition(String key, String fileName, String label, String description,
                              boolean editable, String activation) {
    }
}
