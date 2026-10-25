package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeChatQueryCreateDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.KnowledgeChatMessageVO;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.entity.KnowledgeChatSession;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.KnowledgeChatSessionMapper;
import com.ylcloud.security.ApiKeyPrincipal;
import com.ylcloud.service.rag.RagEvidenceService;
import com.ylcloud.service.space.SpacePermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenApiKnowledgeChatServiceTest {
    private final UserApiKeyService apiKeys=mock(UserApiKeyService.class);
    private final KnowledgeChatSessionMapper sessions=mock(KnowledgeChatSessionMapper.class);
    private final KnowledgeChatMessageMapper messages=mock(KnowledgeChatMessageMapper.class);
    private final KnowledgeChatQueryService queries=mock(KnowledgeChatQueryService.class);
    private final ObjectMapper json=new ObjectMapper();
    private final OpenApiKnowledgeChatService service=new OpenApiKnowledgeChatService(apiKeys,sessions,messages,queries,json);
    private final ApiKeyPrincipal principal=new ApiKeyPrincipal(23L,7L,"prefix","NONE",null,
            Set.of(UserApiKeyService.KNOWLEDGE_RETRIEVE,UserApiKeyService.KNOWLEDGE_AGENT),Set.of(5L,6L));

    @BeforeEach
    void ownSession() {
        KnowledgeChatSession session=new KnowledgeChatSession(); session.setId(8L); session.setUserId(7L);
        when(sessions.getActive(8L,7L)).thenReturn(session);
        when(sessions.getActiveForUpdate(8L,7L)).thenReturn(session);
    }

    @Test
    void newAgentTaskUsesAuthenticatedKeyAndKeepsSessionLockAcrossIdempotencyCheck() {
        KnowledgeChatQueryCreateDTO request=request(List.of(5L),999L);

        service.submit(principal,8L,request," request-1 ",true);

        assertEquals(23L,request.getApiKeyId());
        var order=inOrder(sessions,messages,queries);
        order.verify(sessions).getActiveForUpdate(8L,7L);
        order.verify(messages).getByRequestKey(8L,"request-1");
        order.verify(queries).submit(8L,7L,request,"request-1");
        verify(apiKeys).requireSpace(principal,5L,true);
    }

    @Test
    void assistantTaskCannotSpoofAgentKey() {
        KnowledgeChatQueryCreateDTO request=request(List.of(5L),999L);

        service.submit(principal,8L,request,"request-1",false);

        assertNull(request.getApiKeyId());
        verify(apiKeys).requireSpace(principal,5L,false);
    }

    @Test
    void idempotentReplayCannotReplaceOriginalScopesWithNarrowerBody() throws Exception {
        KnowledgeChatMessage existing=task(List.of(5L,6L),23L);
        when(messages.getByRequestKey(8L,"request-1")).thenReturn(existing);
        doThrow(new BaseException(403,"scope revoked")).when(apiKeys).requireSpace(principal,6L,true);

        assertThrows(BaseException.class,()->service.submit(principal,8L,request(List.of(5L),null),"request-1",true));

        verify(apiKeys).requireSpace(principal,6L,true);
        verifyNoInteractions(queries);
    }

    @Test
    void idempotentReplayReturnsProtectedOriginalAfterCheckingEveryOriginalSpace() throws Exception {
        KnowledgeChatMessage existing=task(List.of(5L,6L),23L);
        when(messages.getByRequestKey(8L,"request-1")).thenReturn(existing);
        KnowledgeChatMessageVO protectedResult=new KnowledgeChatMessageVO(); protectedResult.setContent("protected result");
        when(queries.toVO(existing)).thenReturn(protectedResult);

        assertSame(protectedResult,service.submit(principal,8L,request(List.of(5L),null),"request-1",true));

        var order=inOrder(apiKeys,queries);
        order.verify(apiKeys,times(2)).requireSpace(principal,5L,true);
        order.verify(apiKeys).requireSpace(principal,6L,true);
        order.verify(queries).toVO(existing);
        verify(queries,never()).submit(anyLong(),anyLong(),any(),anyString());
    }

    @Test
    void resultReadRechecksAllOriginalSpaces() throws Exception {
        KnowledgeChatMessage existing=task(List.of(5L,6L),null);
        when(messages.getOwned(11L,8L,7L)).thenReturn(existing);
        doThrow(new BaseException(403,"scope revoked")).when(apiKeys).requireSpace(principal,6L,false);

        assertThrows(BaseException.class,()->service.result(principal,8L,11L,false));

        verify(apiKeys).requireSpace(principal,5L,false);
        verify(apiKeys).requireSpace(principal,6L,false);
        verifyNoInteractions(queries);
    }

    @Test
    void agentResultCannotDowngradeScopeThroughAssistantAlias() throws Exception {
        when(messages.getOwned(11L,8L,7L)).thenReturn(task(List.of(5L),23L));
        doThrow(new BaseException(403,"agent scope required")).when(apiKeys).requireSpace(principal,5L,true);

        assertThrows(BaseException.class,()->service.result(principal,8L,11L,false));

        verify(apiKeys).requireSpace(principal,5L,true);
        verifyNoInteractions(queries);
    }

    @Test
    void unreadableOriginalScopeFailsClosed() throws Exception {
        for(String requestJson:List.of("{}","null","not-json","{\"spaceIds\":[]}","{\"spaceIds\":[null]}")) {
            KnowledgeChatMessage existing=task(List.of(5L),null); existing.setRequestJson(requestJson);
            when(messages.getOwned(11L,8L,7L)).thenReturn(existing);
            assertEquals(403,assertThrows(BaseException.class,()->service.result(principal,8L,11L,false)).getStatusCode());
        }
        verifyNoInteractions(queries);
    }

    @Test
    void foreignSessionAndNonAssistantMessageCannotBeRead() throws Exception {
        when(sessions.getActive(8L,7L)).thenReturn(null);
        assertEquals(404,assertThrows(BaseException.class,()->service.result(principal,8L,11L,false)).getStatusCode());
        when(sessions.getActive(8L,7L)).thenReturn(new KnowledgeChatSession());
        KnowledgeChatMessage userMessage=task(List.of(5L),null); userMessage.setRole("user");
        when(messages.getOwned(11L,8L,7L)).thenReturn(userMessage);
        assertEquals(404,assertThrows(BaseException.class,()->service.result(principal,8L,11L,false)).getStatusCode());
        verifyNoInteractions(queries);
    }

    @Test
    void resultUsesSharedEvidenceProtectionInsteadOfStoredRawContent() throws Exception {
        KnowledgeChatQueryService realQueries=new KnowledgeChatQueryService(sessions,messages,mock(SpacePermissionService.class),
                mock(KnowledgeRagQueryService.class),mock(ConversationContextService.class),json,Runnable::run);
        RagEvidenceService evidence=mock(RagEvidenceService.class);
        realQueries.setEvidenceService(evidence);
        KnowledgeChatMessage existing=task(List.of(5L),null); existing.setContent("revoked secret");
        when(messages.getOwned(11L,8L,7L)).thenReturn(existing);
        doAnswer(invocation->{ KnowledgeChatMessageVO vo=invocation.getArgument(1); vo.setContent("依据不可用");
            vo.setCitationsJson("[]"); vo.setVerificationStatus("UNAVAILABLE"); return null; }).when(evidence).protectMessage(eq(existing),any());
        OpenApiKnowledgeChatService protectedService=new OpenApiKnowledgeChatService(apiKeys,sessions,messages,realQueries,json);

        KnowledgeChatMessageVO result=protectedService.result(principal,8L,11L,false);

        assertEquals("依据不可用",result.getContent());
        assertEquals("UNAVAILABLE",result.getVerificationStatus());
        assertEquals("[]",result.getCitationsJson());
    }

    private KnowledgeChatMessage task(List<Long> spaces,Long originalApiKey) throws Exception {
        KnowledgeChatMessage message=new KnowledgeChatMessage(); message.setId(11L); message.setUserId(7L);
        message.setSessionId(8L); message.setRole("assistant"); message.setTaskStatus("SUCCESS");
        message.setRequestJson(json.writeValueAsString(request(spaces,originalApiKey))); return message;
    }

    private KnowledgeChatQueryCreateDTO request(List<Long> spaces,Long key) {
        KnowledgeChatQueryCreateDTO request=new KnowledgeChatQueryCreateDTO(); request.setQuestion("question");
        request.setSpaceIds(spaces); request.setApiKeyId(key); return request;
    }
}
