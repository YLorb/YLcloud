package com.ylcloud.service.rag.query;

import com.ylcloud.DTO.RagChatMessageDTO;
import com.ylcloud.config.RagProperties;
import com.ylcloud.service.rag.RagChatRequest;
import com.ylcloud.service.rag.RagChatResponse;
import com.ylcloud.service.rag.RagGenerateRequest;
import com.ylcloud.service.rag.RagGenerateResponse;
import com.ylcloud.service.rag.RagModelClient;
import com.ylcloud.service.rag.RerankResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryRewriteServiceTest {

    @Test
    void invokesRewriteHydeStepBackAndMultiQueryPromptsWithHistory() {
        FakeModelClient client = new FakeModelClient(List.of(
                "项目中的 RAG 查询改写功能如何配置",
                "RAG 查询改写配置\nRAG HyDE 检索配置\nRAG 多查询扩展配置\nRAG Step-back 检索配置",
                "知识库文档通常会说明 RAG 查询改写、HyDE、Step-back 和多查询扩展的配置方法。\n这些配置用于提升召回质量。",
                "RAG 检索增强生成系统有哪些查询扩展策略"
        ));
        QueryRewriteService service = new QueryRewriteService(new RagProperties(),client);

        QueryPlan plan = service.plan("它怎么配？",List.of(history("user","RAG 查询改写功能")));

        assertEquals("项目中的 RAG 查询改写功能如何配置",plan.getRewrittenQuery());
        assertEquals(4,plan.getExpandedQueries().size());
        assertTrue(plan.getHydeDocument().contains("知识库文档"));
        assertTrue(plan.getHydeDocument().contains("提升召回质量"));
        assertTrue(plan.getStepBackQuery().contains("查询扩展策略"));
        assertEquals(4,client.prompts.size());
        assertTrue(client.prompts.get(0).contains("对话历史：user: RAG 查询改写功能"));
        assertTrue(client.prompts.get(0).contains("只输出改写后的问题，不要解释"));
        assertTrue(client.prompts.get(1).contains("3 到 5 个适合知识库检索"));
        assertTrue(client.prompts.get(2).contains("生成一段可能的答案"));
        assertTrue(client.prompts.get(3).contains("转化为一个更通用的背景问题"));
    }

    @Test
    void fallsBackWhenModelGenerationFails() {
        FakeModelClient client = new FakeModelClient(List.of());
        client.fail = true;
        QueryRewriteService service = new QueryRewriteService(new RagProperties(),client);

        QueryPlan plan = service.plan("表格如何解析",List.of());

        assertTrue(plan.getWarnings().contains("rewrite_failed"));
        assertTrue(plan.getWarnings().contains("multi_query_failed"));
        assertTrue(plan.getWarnings().contains("hyde_failed"));
        assertTrue(plan.getWarnings().contains("step_back_failed"));
        assertTrue(plan.retrievalQueries().contains("表格如何解析"));
    }

    private RagChatMessageDTO history(String role, String content) {
        RagChatMessageDTO message = new RagChatMessageDTO();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static class FakeModelClient implements RagModelClient {
        private final List<String> outputs;
        private final List<String> prompts = new ArrayList<>();
        private int index;
        private boolean fail;

        private FakeModelClient(List<String> outputs) {
            this.outputs = outputs;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return List.of();
        }

        @Override
        public List<RerankResult> rerank(String query, List<String> documents, Integer topK) {
            return List.of();
        }

        @Override
        public RagChatResponse chat(RagChatRequest request) {
            return new RagChatResponse();
        }

        @Override
        public RagGenerateResponse generate(RagGenerateRequest request) {
            if(fail) {
                throw new IllegalStateException("generation unavailable");
            }
            prompts.add(request.getPrompt());
            RagGenerateResponse response = new RagGenerateResponse();
            response.setText(outputs.get(index++));
            return response;
        }
    }
}
