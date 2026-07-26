package com.ylcloud.controller;

import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.context.OpenApiContext;
import com.ylcloud.interceptor.OpenApiKeyInterceptor;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.service.FileService;
import com.ylcloud.service.KnowledgeChatQueryService;
import com.ylcloud.service.KnowledgeChatSessionService;
import com.ylcloud.service.OpenApiVersionPolicyService;
import com.ylcloud.service.SpaceRagService;
import com.ylcloud.service.SpaceService;
import com.ylcloud.service.UserApiKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OpenApiAgentBindingTest {
    @AfterEach
    void clearContext() {
        OpenApiContext.clear();
    }

    @Test
    void agentQueryBindsAuthenticatedApiKeyIntoPersistedWorkflowInput() {
        UserApiKeyService apiKeys = mock(UserApiKeyService.class);
        KnowledgeChatQueryService queries = mock(KnowledgeChatQueryService.class);
        OpenApiController controller = new OpenApiController(apiKeys,mock(OpenApiVersionPolicyService.class),
                mock(FileService.class),mock(FileInfoMapper.class),mock(SpaceService.class),mock(SpaceRagService.class),
                mock(KnowledgeChatSessionService.class),queries);
        OpenApiContext.set(new ApiKeyPrincipal(23L,7L,"prefix","NONE",null,
                Set.of(UserApiKeyService.KNOWLEDGE_AGENT),Set.of(5L)));
        KnowledgeChatQueryCreateDTO dto = new KnowledgeChatQueryCreateDTO();
        dto.setQuestion("question");
        dto.setSpaceIds(List.of(5L));
        dto.setApiKeyId(999L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(OpenApiKeyInterceptor.TRACE_ATTRIBUTE,"trace_1234");

        controller.agentQuery(8L,dto,"idem-agent-123",request);

        assertEquals(23L,dto.getApiKeyId());
        verify(apiKeys).requireSpace(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(true));
        verify(queries).submit(8L,7L,dto,"idem-agent-123");
    }
}
