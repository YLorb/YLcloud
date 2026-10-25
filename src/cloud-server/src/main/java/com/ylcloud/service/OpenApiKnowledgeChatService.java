package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.KnowledgeChatMessageVO;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** API-key scope is rechecked against stored task inputs before any existing answer is returned. */
@Service
@RequiredArgsConstructor
public class OpenApiKnowledgeChatService {
    private final UserApiKeyService apiKeys;
    private final KnowledgeChatSessionMapper sessions;
    private final KnowledgeChatMessageMapper messages;
    private final KnowledgeChatQueryService queries;
    private final ObjectMapper json;

    @Transactional
    public KnowledgeChatMessageVO submit(ApiKeyPrincipal principal,Long sessionId,
                                         KnowledgeChatQueryCreateDTO dto,String requestKey,boolean agent) {
        String key=normalizeKey(requestKey);
        // The same session lock used by submit closes the gap between idempotency inspection and insert.
        if(sessions.getActiveForUpdate(sessionId,principal.userId())==null) throw notFound();
        requireSpaces(principal,dto.getSpaceIds(),agent);
        KnowledgeChatMessage existing=messages.getByRequestKey(sessionId,key);
        if(existing!=null) {
            requireOriginalScope(principal,sessionId,existing,agent);
            return queries.toVO(existing);
        }
        dto.setApiKeyId(agent ? principal.keyId() : null);
        return queries.submit(sessionId,principal.userId(),dto,key);
    }

    public KnowledgeChatMessageVO result(ApiKeyPrincipal principal,Long sessionId,Long messageId,boolean agent) {
        if(sessions.getActive(sessionId,principal.userId())==null) throw notFound();
        KnowledgeChatMessage message=messages.getOwned(messageId,sessionId,principal.userId());
        requireOriginalScope(principal,sessionId,message,agent);
        return queries.toVO(message);
    }

    private void requireOriginalScope(ApiKeyPrincipal principal,Long sessionId,KnowledgeChatMessage message,boolean agent) {
        if(message==null || !"assistant".equals(message.getRole())
                || !Objects.equals(principal.userId(),message.getUserId())
                || !Objects.equals(sessionId,message.getSessionId())) throw notFound();
        KnowledgeChatQueryCreateDTO original;
        try {
            original=json.readValue(message.getRequestJson(),KnowledgeChatQueryCreateDTO.class);
        } catch(Exception malformed) {
            throw new BaseException(403,"无法核验原任务的知识库授权范围");
        }
        if(original==null) throw new BaseException(403,"无法核验原任务的知识库授权范围");
        // An agent task also requires agent scope when addressed through the assistant alias.
        requireSpaces(principal,original.getSpaceIds(),agent || original.getApiKeyId()!=null);
    }

    private void requireSpaces(ApiKeyPrincipal principal,List<Long> spaces,boolean agent) {
        if(spaces==null || spaces.isEmpty() || spaces.stream().anyMatch(id->id==null || id<=0))
            throw new BaseException(403,"无法核验任务的知识库授权范围");
        spaces.stream().distinct().forEach(space->apiKeys.requireSpace(principal,space,agent));
    }

    private String normalizeKey(String requestKey) {
        String key=requestKey==null ? "" : requestKey.trim();
        if(key.isEmpty() || key.length()>100 || !key.matches("^[A-Za-z0-9._:-]+$"))
            throw new BaseException("Idempotency-Key 必须为 1-100 个字母、数字、点、下划线、冒号或短横线");
        return key;
    }

    private BaseException notFound() { return new BaseException(404,"问答任务不存在"); }
}
