package com.ylcloud.service.rag.retriever;

import java.util.List;

public interface RagKeywordTokenizer {
    List<String> tokenize(String text);
}
