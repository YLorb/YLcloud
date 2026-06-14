package com.ylcloud.service.rag;

import java.util.List;

public interface RagModelClient {
    List<float[]> embed(List<String> texts);

    List<RerankResult> rerank(String query, List<String> documents, Integer topK);

    RagChatResponse chat(RagChatRequest request);
}
