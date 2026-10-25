package com.ylcloud.controller;

import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.context.OpenApiContext;
import com.ylcloud.interceptor.OpenApiKeyInterceptor;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.service.FileService;
import com.ylcloud.service.OpenApiKnowledgeChatService;
import com.ylcloud.service.KnowledgeChatSessionService;
import com.ylcloud.service.OpenApiVersionPolicyService;
import com.ylcloud.service.space.SpaceRagService;
import com.ylcloud.service.space.SpaceService;
import com.ylcloud.service.UserApiKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
    void agentQueryUsesAuthenticatedPrincipalAtTheOpenApiScopeBoundary() {
        UserApiKeyService apiKeys = mock(UserApiKeyService.class);
        OpenApiKnowledgeChatService queries = mock(OpenApiKnowledgeChatService.class);
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

        verify(queries).submit(OpenApiContext.require(),8L,dto,"idem-agent-123",true);
    }

    @Test
    void resultRoutesUseCurrentPrincipalAndDisableResponseCaching() {
        OpenApiKnowledgeChatService queries=mock(OpenApiKnowledgeChatService.class);
        OpenApiController controller=new OpenApiController(mock(UserApiKeyService.class),mock(OpenApiVersionPolicyService.class),
                mock(FileService.class),mock(FileInfoMapper.class),mock(SpaceService.class),mock(SpaceRagService.class),
                mock(KnowledgeChatSessionService.class),queries);
        ApiKeyPrincipal principal=new ApiKeyPrincipal(23L,7L,"prefix","NONE",null,
                Set.of(UserApiKeyService.KNOWLEDGE_AGENT),Set.of(5L));
        OpenApiContext.set(principal);
        MockHttpServletRequest request=new MockHttpServletRequest();
        MockHttpServletResponse agentResponse=new MockHttpServletResponse();
        MockHttpServletResponse assistantResponse=new MockHttpServletResponse();

        controller.agentResult(8L,11L,request,agentResponse);
        controller.assistantResult(8L,12L,request,assistantResponse);

        verify(queries).result(principal,8L,11L,true);
        verify(queries).result(principal,8L,12L,false);
        assertEquals("private, no-store",agentResponse.getHeader("Cache-Control"));
        assertEquals("private, no-store",assistantResponse.getHeader("Cache-Control"));
    }
}
