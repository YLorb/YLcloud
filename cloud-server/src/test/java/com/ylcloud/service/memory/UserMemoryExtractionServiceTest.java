package com.ylcloud.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.config.RagProperties;
import com.ylcloud.mapper.KnowledgeChatMessageMapper;
import com.ylcloud.mapper.UserMemoryExtractionTaskMapper;
import com.ylcloud.service.rag.RagGenerateResponse;
import com.ylcloud.service.rag.RagModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemoryExtractionServiceTest {
    @Test
    void extractsOnlyExplicitNonSensitiveUserMemories() throws Exception {
        UserMemoryExtractionTaskMapper taskMapper = mock(UserMemoryExtractionTaskMapper.class);
        KnowledgeChatMessageMapper messageMapper = mock(KnowledgeChatMessageMapper.class);
        UserMemoryService memoryService = mock(UserMemoryService.class);
        RagModelClient modelClient = mock(RagModelClient.class);
        RagGenerateResponse response = new RagGenerateResponse();
        response.setText("```json\n[" +
                "{\"type\":\"PREFERENCE\",\"key\":\"response.language\",\"content\":\"用户偏好中文回答\",\"confidence\":0.95,\"userConfirmed\":true}," +
                "{\"type\":\"FACT\",\"key\":\"api-key\",\"content\":\"API key 是 abc\",\"confidence\":0.99,\"userConfirmed\":true}]\n```");
        when(modelClient.generate(any())).thenReturn(response);
        UserMemoryExtractionService service = new UserMemoryExtractionService(taskMapper,messageMapper,memoryService,
                modelClient,new RagProperties(),new ObjectMapper(),Runnable::run);

        List<UserMemoryCandidate> candidates = service.extract("请一直使用中文回答");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).key()).isEqualTo("response.language");
        assertThat(candidates.get(0).userConfirmed()).isTrue();
    }
}
