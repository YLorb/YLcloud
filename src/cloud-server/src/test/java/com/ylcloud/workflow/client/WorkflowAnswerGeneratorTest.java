package com.ylcloud.workflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.KnowledgeRagQueryDTO;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.KnowledgeRagQueryVO;
import com.ylcloud.entity.KnowledgeChatMessage;
import com.ylcloud.service.KnowledgeRagQueryService;
import com.ylcloud.service.rag.RagModelClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowAnswerGeneratorTest {
    @Test
    void scopedWorkflowUsesFreshAuthorizedRetrievalNotFrozenToolProse() {
        RagModelClient model = mock(RagModelClient.class);
        KnowledgeRagQueryService query = mock(KnowledgeRagQueryService.class);
        WorkflowAnswerGenerator generator = new WorkflowAnswerGenerator(model, new ObjectMapper());
        generator.setQueryService(query);
        KnowledgeChatMessage message = message("{\"question\":\"如何配置\",\"spaceIds\":[5],\"retrievalMode\":\"balanced\"}");
        KnowledgeRagQueryVO result = new KnowledgeRagQueryVO();
        result.setAnswerId("trusted-answer"); result.setAnswer("有依据的回答");
        when(query.query(any(),eq(7L))).thenReturn(result);
        assertSame(result,generator.generateVerified(message,null));
        ArgumentCaptor<KnowledgeRagQueryDTO> capture = ArgumentCaptor.forClass(KnowledgeRagQueryDTO.class);
        verify(query).query(capture.capture(),eq(7L));
        assertEquals(List.of(5L),capture.getValue().getSpaceIds());
        assertEquals("如何配置",capture.getValue().getQuestion());
        assertTrue(capture.getValue().getHistory().isEmpty());
        verifyNoInteractions(model);
    }

    @Test
    void noKnowledgeScopeExplicitlyRejectsUnverifiedWorkflowAnswer() {
        RagModelClient model = mock(RagModelClient.class);
        KnowledgeRagQueryService query = mock(KnowledgeRagQueryService.class);
        WorkflowAnswerGenerator generator = new WorkflowAnswerGenerator(model,new ObjectMapper());
        generator.setQueryService(query);
        BaseException error = assertThrows(BaseException.class,
                () -> generator.generateVerified(message("{\"question\":\"执行操作\",\"spaceIds\":[]}"),null));
        assertTrue(error.getMessage().contains("WORKFLOW_NO_KNOWLEDGE_SCOPE"));
        verifyNoInteractions(model,query);
    }

    @Test
    void missingTrustedAnswerIdIsNeverAccepted() {
        RagModelClient model = mock(RagModelClient.class);
        KnowledgeRagQueryService query = mock(KnowledgeRagQueryService.class);
        WorkflowAnswerGenerator generator = new WorkflowAnswerGenerator(model,new ObjectMapper());
        generator.setQueryService(query);
        when(query.query(any(),eq(7L))).thenReturn(new KnowledgeRagQueryVO());
        assertThrows(BaseException.class,() -> generator.generateVerified(message("{\"question\":\"问题\",\"spaceIds\":[5]}"),null));
    }

    private KnowledgeChatMessage message(String request) {
        KnowledgeChatMessage value = new KnowledgeChatMessage();
        value.setUserId(7L); value.setRequestJson(request);
        return value;
    }
}
