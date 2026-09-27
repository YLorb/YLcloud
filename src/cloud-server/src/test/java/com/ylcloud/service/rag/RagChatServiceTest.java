package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagChatServiceTest {

    @Test
    void emptyRetrievalIsExplicitNoAnswer() {
        RagModelClient client = mock(RagModelClient.class);
        RagChatService service = new RagChatService(client,new RagProperties());

        RagChatResult result = service.answer("不存在的问题",List.of(),null);

        assertTrue(result.isSuccess());
        assertTrue(result.isNoAnswer());
        verify(client,never()).chat(any());
    }

    @Test
    void configuredNoAnswerReturnedByModelIsExplicitNoAnswer() {
        RagProperties properties = new RagProperties();
        RagModelClient client = mock(RagModelClient.class);
        RagChatResponse response = new RagChatResponse();
        response.setAnswer(properties.getChat().getNoAnswerText());
        when(client.chat(any())).thenReturn(response);
        RagChatService service = new RagChatService(client,properties);

        RagChatResult result = service.answer("无关问题",List.of(chunk()),null);

        assertTrue(result.isSuccess());
        assertTrue(result.isNoAnswer());
    }

    @Test
    void semanticNoAnswerVariantIsRecognizedWithoutExactTextMatch() {
        RagModelClient client = mock(RagModelClient.class);
        RagChatResponse response = new RagChatResponse();
        response.setAnswer("当前知识库没有检索到足够的信息，因此无法回答。");
        when(client.chat(any())).thenReturn(response);
        RagChatService service = new RagChatService(client,new RagProperties());

        RagChatResult result = service.answer("无关问题",List.of(chunk()),null);

        assertTrue(result.isNoAnswer());
    }

    @Test
    void emptyModelAnswerBecomesExplicitFailedNoAnswer() {
        RagModelClient client = mock(RagModelClient.class);
        when(client.chat(any())).thenReturn(new RagChatResponse());
        RagChatService service = new RagChatService(client,new RagProperties());

        RagChatResult result = service.answer("测试问题",List.of(chunk()),null);

        assertFalse(result.isSuccess());
        assertTrue(result.isNoAnswer());
    }

    @Test
    void modelUnavailableFallbackKeepsRetrievedEvidenceVisible() {
        RagProperties properties = new RagProperties();
        properties.getChat().setEnabled(false);
        RagModelClient client = mock(RagModelClient.class);
        RagChatService service = new RagChatService(client,properties);

        RagChatResult result = service.answer("有效问题",List.of(chunk()),null);

        assertFalse(result.isSuccess());
        assertFalse(result.isNoAnswer());
        verify(client,never()).chat(any());
    }

    @Test
    void groundedAnswerIsNotNoAnswer() {
        RagModelClient client = mock(RagModelClient.class);
        RagChatResponse response = new RagChatResponse();
        response.setAnswer("答案是 ORBIT-7319。[1]");
        when(client.chat(any())).thenReturn(response);
        RagChatService service = new RagChatService(client,new RagProperties());

        RagChatResult result = service.answer("发射码是什么？",List.of(chunk()),null);

        assertTrue(result.isSuccess());
        assertFalse(result.isNoAnswer());
    }

    @Test
    void sendsTenCompleteChunksWithoutCharacterTruncation() {
        RagProperties properties = new RagProperties();
        properties.getChat().setMaxContextChunks(20);
        RagModelClient client = mock(RagModelClient.class);
        RagChatResponse response = new RagChatResponse();
        response.setAnswer("有依据的回答。[1]");
        when(client.chat(any())).thenReturn(response);
        List<FileRagChunk> chunks = new ArrayList<>();
        for(int i = 0; i < 11; i++) {
            FileRagChunk value = new FileRagChunk();
            value.setId((long) i + 1);
            value.setContent("chunk-" + i + ":" + "证据".repeat(1000) + ":end-" + i);
            chunks.add(value);
        }

        RagChatResult result = new RagChatService(client,properties).answer("问题",chunks,null);

        assertTrue(result.isSuccess());
        ArgumentCaptor<RagChatRequest> request = ArgumentCaptor.forClass(RagChatRequest.class);
        verify(client).chat(request.capture());
        assertEquals(10,request.getValue().getContexts().size());
        for(int i = 0; i < 10; i++) {
            assertEquals("[" + (i + 1) + "] " + chunks.get(i).getContent(),
                    request.getValue().getContexts().get(i));
        }
    }

    private FileRagChunk chunk() {
        FileRagChunk chunk = new FileRagChunk();
        chunk.setId(1L);
        chunk.setContent("发射码是 ORBIT-7319。");
        return chunk;
    }
}
