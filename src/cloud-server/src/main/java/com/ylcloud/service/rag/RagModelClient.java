package com.ylcloud.service.rag;

import java.util.List;

public interface RagModelClient {
    /**
     * 调用模型服务生成文本向量。
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    List<float[]> embed(List<String> texts);

    /**
     * 调用模型服务对候选文档进行重排序。
     *
     * @param query 查询内容
     * @param documents 文档列表
     * @param topK 召回数量
     * @return 重排序结果列表
     */
    List<RerankResult> rerank(String query, List<String> documents, Integer topK);

    /**
     * 调用模型服务生成问答结果。
     *
     * @param request 请求对象
     * @return 问答响应结果
     */
    RagChatResponse chat(RagChatRequest request);

    /**
     * 调用模型服务执行通用文本生成。
     *
     * @param request 生成请求
     * @return 生成响应
     */
    RagGenerateResponse generate(RagGenerateRequest request);
}
