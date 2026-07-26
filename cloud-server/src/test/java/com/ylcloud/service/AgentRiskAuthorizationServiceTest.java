package com.ylcloud.service;

import com.ylcloud.DTO.AgentRiskAuthorizationCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.AgentRiskAuthorization;
import com.ylcloud.mapper.AgentRiskAuthorizationMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRiskAuthorizationServiceTest {
    @Test
    void webAuthorizationRequiresDisclosureAndReplacesPreviousSubjectGrant() {
        AgentRiskAuthorizationMapper mapper = mock(AgentRiskAuthorizationMapper.class);
        AgentRiskAuthorization existing = authorization(1L,7L,null,"PERSISTENT",null);
        when(mapper.lockActive(7L,null)).thenReturn(existing);
        when(mapper.insert(any())).thenAnswer(call -> {
            AgentRiskAuthorization value = call.getArgument(0);
            value.setId(2L);
            return 1;
        });
        AgentRiskAuthorizationCreateDTO dto = new AgentRiskAuthorizationCreateDTO();
        dto.setMode("ALLOW_ONCE");
        dto.setRiskAcknowledged(true);

        var issued = new AgentRiskAuthorizationService(mapper).issueForWeb(7L,dto);

        assertEquals("WEB_ACCOUNT",issued.getSubjectType());
        assertEquals("ALLOW_ONCE",issued.getMode());
        assertNotNull(issued.getExpiresAt());
        verify(mapper).revoke(eq(1L),eq("REVOKED"),eq(7L),any());
        verify(mapper).insert(any());
    }

    @Test
    void allowOnceUsesConditionalAtomicConsumeAndCannotBeReused() {
        AgentRiskAuthorizationMapper mapper = mock(AgentRiskAuthorizationMapper.class);
        AgentRiskAuthorization once = authorization(3L,7L,null,"ALLOW_ONCE",LocalDateTime.now().plusMinutes(5));
        when(mapper.lockActive(7L,null)).thenReturn(once);
        when(mapper.consumeOnce(eq(3L),any(),any())).thenReturn(1,0);
        AgentRiskAuthorizationService service = new AgentRiskAuthorizationService(mapper);

        service.authorizeHighRisk(7L,null,"11111111-1111-4111-8111-111111111111");
        assertThrows(BaseException.class,() -> service.authorizeHighRisk(
                7L,null,"22222222-2222-4222-8222-222222222222"));
    }

    @Test
    void apiKeyCannotUseAccountOrAnotherKeyAuthorization() {
        AgentRiskAuthorizationMapper mapper = mock(AgentRiskAuthorizationMapper.class);
        when(mapper.lockActive(7L,22L)).thenReturn(null);
        when(mapper.insert(any())).thenAnswer(call -> {
            AgentRiskAuthorization value = call.getArgument(0);
            value.setId(5L);
            return 1;
        });
        AgentRiskAuthorizationService service = new AgentRiskAuthorizationService(mapper);

        assertThrows(BaseException.class,() -> service.authorizeHighRisk(7L,22L,"invoke-1"));
        AgentRiskAuthorization issued = service.issueForApiKey(7L,22L,"ALLOW_ONCE",null,true,7L);

        assertEquals(22L,issued.getApiKeyId());
        assertEquals("ALLOW_ONCE",issued.getAuthorizationMode());
        verify(mapper,org.mockito.Mockito.times(2)).lockActive(7L,22L);
        verify(mapper,never()).consumeOnce(anyLong(),any(),any());
    }

    @Test
    void persistentAuthorizationDoesNotConsumeAndRevocationIsOwnerBound() {
        AgentRiskAuthorizationMapper mapper = mock(AgentRiskAuthorizationMapper.class);
        AgentRiskAuthorization persistent = authorization(4L,7L,null,"PERSISTENT",null);
        when(mapper.lockActive(7L,null)).thenReturn(persistent);
        when(mapper.lockById(4L)).thenReturn(persistent);
        AgentRiskAuthorizationService service = new AgentRiskAuthorizationService(mapper);

        service.authorizeHighRisk(7L,null,"invoke-1");
        service.revoke(7L,4L);

        verify(mapper,never()).consumeOnce(anyLong(),any(),any());
        verify(mapper).revoke(eq(4L),eq("REVOKED"),eq(7L),any());
        assertThrows(BaseException.class,() -> service.revoke(8L,4L));
    }

    private AgentRiskAuthorization authorization(Long id,Long userId,Long apiKeyId,String mode,LocalDateTime expiresAt) {
        AgentRiskAuthorization value = new AgentRiskAuthorization();
        value.setId(id);
        value.setUserId(userId);
        value.setApiKeyId(apiKeyId);
        value.setAuthorizationMode(mode);
        value.setAuthorizationStatus("ACTIVE");
        value.setRiskAcknowledged(true);
        value.setExpiresAt(expiresAt);
        return value;
    }
}
