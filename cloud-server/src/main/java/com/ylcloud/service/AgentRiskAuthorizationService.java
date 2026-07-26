package com.ylcloud.service;

import com.ylcloud.DTO.AgentRiskAuthorizationCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.AgentRiskAuthorizationVO;
import com.ylcloud.entity.AgentRiskAuthorization;
import com.ylcloud.mapper.AgentRiskAuthorizationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentRiskAuthorizationService {
    private static final String ACTIVE = "ACTIVE";
    private static final String ALLOW_ONCE = "ALLOW_ONCE";
    private static final String PERSISTENT = "PERSISTENT";
    private final AgentRiskAuthorizationMapper mapper;

    @Transactional
    public AgentRiskAuthorizationVO issueForWeb(Long userId,AgentRiskAuthorizationCreateDTO dto) {
        return toVO(replace(userId,null,dto.getMode(),dto.getExpiresAt(),dto.isRiskAcknowledged(),userId));
    }

    @Transactional
    public AgentRiskAuthorization replaceForApiKey(Long userId,Long apiKeyId,boolean enabled,
                                                    LocalDateTime expiresAt,boolean riskAcknowledged) {
        if(apiKeyId == null || apiKeyId < 1) throw new BaseException("API Key 标识无效");
        AgentRiskAuthorization active = mapper.lockActive(userId,apiKeyId);
        LocalDateTime now = LocalDateTime.now();
        if(active != null) mapper.revoke(active.getId(),"REVOKED",userId,now);
        if(!enabled) return null;
        return create(userId,apiKeyId,PERSISTENT,expiresAt,riskAcknowledged,now);
    }

    @Transactional
    public AgentRiskAuthorization issueForApiKey(Long userId,Long apiKeyId,String mode,
                                                  LocalDateTime expiresAt,boolean riskAcknowledged,Long operatorId) {
        if(apiKeyId == null || apiKeyId < 1) throw new BaseException("API Key 标识无效");
        return replace(userId,apiKeyId,mode,expiresAt,riskAcknowledged,operatorId);
    }

    @Transactional
    public void authorizeHighRisk(Long userId,Long apiKeyId,String invocationId) {
        AgentRiskAuthorization authorization = mapper.lockActive(userId,apiKeyId);
        LocalDateTime now = LocalDateTime.now();
        if(authorization == null) throw new BaseException(403,"高风险 Agent 操作未授权");
        if(authorization.getExpiresAt() != null && !authorization.getExpiresAt().isAfter(now)) {
            mapper.revoke(authorization.getId(),"EXPIRED",userId,now);
            throw new BaseException(403,"高风险 Agent 授权已过期");
        }
        if(!Boolean.TRUE.equals(authorization.getRiskAcknowledged())) {
            throw new BaseException(403,"高风险 Agent 授权无效");
        }
        if(ALLOW_ONCE.equals(authorization.getAuthorizationMode())
                && mapper.consumeOnce(authorization.getId(),invocationId,now) != 1) {
            throw new BaseException(409,"一次性高风险授权已使用");
        }
        if(!ALLOW_ONCE.equals(authorization.getAuthorizationMode())
                && !PERSISTENT.equals(authorization.getAuthorizationMode())) {
            throw new BaseException(403,"高风险 Agent 授权无效");
        }
    }

    @Transactional
    public void revoke(Long userId,Long authorizationId) {
        AgentRiskAuthorization authorization = mapper.lockById(authorizationId);
        if(authorization == null || !userId.equals(authorization.getUserId()) || !ACTIVE.equals(authorization.getAuthorizationStatus())) {
            throw new BaseException("高风险 Agent 授权不存在或已撤销");
        }
        mapper.revoke(authorizationId,"REVOKED",userId,LocalDateTime.now());
    }

    public List<AgentRiskAuthorizationVO> list(Long userId) {
        return mapper.listByUser(userId).stream().map(this::toVO).toList();
    }

    private AgentRiskAuthorization replace(Long userId,Long apiKeyId,String mode,LocalDateTime expiresAt,
                                           boolean riskAcknowledged,Long operatorId) {
        if(!riskAcknowledged) throw new BaseException("必须明确接受高风险 Agent 授权风险");
        if(!ALLOW_ONCE.equals(mode) && !PERSISTENT.equals(mode)) throw new BaseException("高风险授权模式无效");
        LocalDateTime now = LocalDateTime.now();
        if(expiresAt != null && !expiresAt.isAfter(now)) throw new BaseException("授权到期时间必须晚于当前时间");
        if(ALLOW_ONCE.equals(mode)) {
            LocalDateTime maximum = now.plusMinutes(15);
            if(expiresAt == null) expiresAt = now.plusMinutes(5);
            if(expiresAt.isAfter(maximum)) throw new BaseException("一次性高风险授权最长有效 15 分钟");
        }
        AgentRiskAuthorization active = mapper.lockActive(userId,apiKeyId);
        if(active != null) mapper.revoke(active.getId(),"REVOKED",operatorId,now);
        return create(userId,apiKeyId,mode,expiresAt,true,now);
    }

    private AgentRiskAuthorization create(Long userId,Long apiKeyId,String mode,LocalDateTime expiresAt,
                                          boolean acknowledged,LocalDateTime now) {
        AgentRiskAuthorization value = new AgentRiskAuthorization();
        value.setUserId(userId);
        value.setApiKeyId(apiKeyId);
        value.setAuthorizationMode(mode);
        value.setAuthorizationStatus(ACTIVE);
        value.setExpiresAt(expiresAt);
        value.setRiskAcknowledged(acknowledged);
        value.setCreateTime(now);
        value.setUpdateTime(now);
        mapper.insert(value);
        return value;
    }

    private AgentRiskAuthorizationVO toVO(AgentRiskAuthorization value) {
        AgentRiskAuthorizationVO vo = new AgentRiskAuthorizationVO();
        vo.setId(value.getId());
        vo.setSubjectType(value.getApiKeyId() == null ? "WEB_ACCOUNT" : "API_KEY");
        vo.setApiKeyId(value.getApiKeyId());
        vo.setMode(value.getAuthorizationMode());
        vo.setStatus(value.getAuthorizationStatus());
        vo.setExpiresAt(value.getExpiresAt());
        vo.setConsumedInvocationId(value.getConsumedInvocationId());
        vo.setConsumedAt(value.getConsumedAt());
        vo.setRevokedAt(value.getRevokedAt());
        vo.setCreateTime(value.getCreateTime());
        vo.setUpdateTime(value.getUpdateTime());
        return vo;
    }
}
