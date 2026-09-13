package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.FileRagChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagRerankServiceTest {

    @Test
    void rerankUsesConfiguredTopKAsOutputLimit() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setTopK(5);
        FakeModelClient client = new FakeModelClient();
        RagRerankService service = new RagRerankService(client,properties);

        List<FileRagChunk> result = service.rerank("query",chunks(7),10);

        assertEquals(5,client.requestedTopK);
        assertEquals(5,result.size());
        assertEquals(7,client.documentCount);
    }

    @Test
    void disabledRerankStillUsesConfiguredTopKLimitWithoutCallingModel() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(false);
        properties.getRerank().setTopK(3);
        FakeModelClient client = new FakeModelClient();
        RagRerankService service = new RagRerankService(client,properties);

        List<FileRagChunk> result = service.rerank("query",chunks(7),10);

        assertEquals(3,result.size());
        assertEquals(0,client.callCount);
    }

    @Test
    void emptyRerankResponseFallsBackToOriginalOrderAndConfiguredTopK() {
        RagProperties properties = new RagProperties();
        properties.getRerank().setTopK(2);
        FakeModelClient client = new FakeModelClient();
        client.returnEmpty = true;
        RagRerankService service = new RagRerankService(client,properties);

        List<FileRagChunk> result = service.rerank("query",chunks(5),5);

        assertEquals(2,result.size());
        assertEquals(1L,result.get(0).getId());
        assertEquals(2L,result.get(1).getId());
        assertEquals(1,client.callCount);
    }

    private List<FileRagChunk> chunks(int count) {
        List<FileRagChunk> chunks = new ArrayList<>();
        for(int i = 0; i < count; i++) {
            FileRagChunk chunk = new FileRagChunk();
            chunk.setId((long) i + 1);
            chunk.setContent("content-" + i);
            chunks.add(chunk);
        }
        return chunks;
    }

    private static class FakeModelClient implements RagModelClient {
        private int requestedTopK;
        private int documentCount;
        private int callCount;
        private boolean returnEmpty;

        @Override
        public List<float[]> embed(List<String> texts) {
            return List.of();
        }

        @Override
        public List<RerankResult> rerank(String query, List<String> documents, Integer topK) {
            callCount++;
            requestedTopK = topK;
            documentCount = documents.size();
            if(returnEmpty) {
                return List.of();
            }
            List<RerankResult> results = new ArrayList<>();
            for(int i = 0; i < Math.min(topK,documents.size()); i++) {
                RerankResult result = new RerankResult();
                result.setIndex(i);
                result.setScore((double) documents.size() - i);
                results.add(result);
            }
            return results;
        }

        @Override
        public RagChatResponse chat(RagChatRequest request) {
            return new RagChatResponse();
        }

        @Override
        public RagGenerateResponse generate(RagGenerateRequest request) {
            return new RagGenerateResponse();
        }
    }
}
