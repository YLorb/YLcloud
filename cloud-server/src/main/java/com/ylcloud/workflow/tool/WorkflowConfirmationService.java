package com.ylcloud.workflow.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.DTO.WorkflowConfirmationCreateDTO;
import com.ylcloud.workflow.contract.WorkflowContracts.ConfirmationGrant;
import com.ylcloud.workflow.contract.WorkflowContracts.ConfirmationMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

/** 确认凭证由 Java 权威存储；请求中的自描述 Grant 不能替代数据库校验。 */
@Service
public class WorkflowConfirmationService {
    private final WorkflowConfirmationMapper mapper;
    private final ObjectMapper objectMapper;
    private final WorkflowToolRegistry registry;
    private final ToolArgumentsCanonicalizer canonicalizer;

    public WorkflowConfirmationService(WorkflowConfirmationMapper mapper, ObjectMapper objectMapper,
                                       WorkflowToolRegistry registry, ToolArgumentsCanonicalizer canonicalizer) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.canonicalizer = canonicalizer;
    }

    public ConfirmationGrant issue(long userId, WorkflowConfirmationCreateDTO dto) {
        WorkflowToolHandler handler = registry.require(dto.getToolName());
        if (handler.riskLevel() != com.ylcloud.workflow.contract.WorkflowContracts.RiskLevel.HIGH)
            throw new BaseException("此 Tool 不需要高风险确认");
        if (dto.getMode() == ConfirmationMode.ALLOW_ONCE && dto.getSimilarityScope() != null)
            throw new BaseException("一次性确认不能包含类似参数范围");
        if (dto.getMode() == ConfirmationMode.ALLOW_SIMILAR) {
            if (dto.getSimilarityScope() == null || dto.getSimilarityScope().isEmpty())
                throw new BaseException("类似操作确认必须限定参数范围");
            dto.getSimilarityScope().forEach((key, value) -> {
                if (!java.util.Objects.equals(dto.getArguments().get(key), value))
                    throw new BaseException("类似操作范围必须来自当前参数");
            });
        }
        Instant issuedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(dto.getTtlSeconds() == null ? 300 : dto.getTtlSeconds());
        ConfirmationGrant grant = new ConfirmationGrant(dto.getMode(), java.util.UUID.randomUUID(), userId,
                dto.getToolName(), canonicalizer.hash(dto.getArguments()), dto.getSimilarityScope(), issuedAt, expiresAt);
        WorkflowConfirmationRecord record = new WorkflowConfirmationRecord();
        record.setGrantId(grant.grantId().toString()); record.setUserId(userId); record.setToolName(grant.toolName());
        record.setParameterHash(grant.parameterHash()); record.setGrantMode(grant.mode().name());
        try { record.setSimilarityScopeJson(grant.similarityScope() == null ? null : objectMapper.writeValueAsString(grant.similarityScope())); }
        catch (Exception exception) { throw new BaseException("确认参数无法持久化"); }
        record.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()));
        mapper.insert(record);
        return grant;
    }

    public void revoke(long userId, java.util.UUID grantId) {
        if (mapper.revoke(grantId.toString(), userId) == 0) throw new BaseException("确认凭证不存在或已撤销");
    }

    @Transactional
    public void authorize(ConfirmationGrant grant, ToolInvocationContext context, String toolName,
                          String argumentsHash, Map<String, Object> arguments) {
        if (grant == null) throw new BaseException(409, "此操作需要用户确认");
        WorkflowConfirmationRecord stored = mapper.getForUpdate(grant.grantId().toString());
        if (stored == null || Boolean.TRUE.equals(stored.getRevoked())
                || !context.userIdEquals(stored.getUserId())
                || !toolName.equals(stored.getToolName())
                || !grant.mode().name().equals(stored.getGrantMode())
                || !grant.parameterHash().equals(stored.getParameterHash())
                || stored.getExpiresAt() == null || !grant.expiresAt().isAfter(Instant.now())
                || grant.expiresAt().getEpochSecond() != stored.getExpiresAt()
                    .atZone(ZoneId.systemDefault()).toInstant().getEpochSecond()) {
            throw new BaseException(403, "确认凭证无效或已撤销");
        }
        if (grant.mode() == ConfirmationMode.ALLOW_ONCE) {
            if (!argumentsHash.equals(stored.getParameterHash())
                    || mapper.consumeOnce(stored.getGrantId(), context.invocationId().toString()) == 0)
                throw new BaseException(409, "一次性确认已使用");
            return;
        }
        Map<String, Object> scope = readScope(stored.getSimilarityScopeJson());
        if (scope.isEmpty() || scope.entrySet().stream().anyMatch(entry ->
                !java.util.Objects.equals(arguments.get(entry.getKey()), entry.getValue()))) {
            throw new BaseException(403, "参数超出类似操作授权范围");
        }
    }

    private Map<String, Object> readScope(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new BaseException(403, "确认凭证范围无效");
        }
    }
}
