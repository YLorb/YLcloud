package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceRagQueryLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

/**
 * 空间 RAG 问答日志 Mapper。
 */
@Mapper
public interface SpaceRagQueryLogMapper {

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_rag_query_log(space_id, user_id, question, answer, hit_chunk_ids, model_name, top_k, temperature, prompt_tokens, completion_tokens, total_tokens, success, error_message, createtime) " +
            "values(#{spaceId}, #{userId}, #{question}, #{answer}, #{hitChunkIds}, #{modelName}, #{topK}, #{temperature}, #{promptTokens}, #{completionTokens}, #{totalTokens}, #{success}, #{errorMessage}, #{createtime})")
    int insert(SpaceRagQueryLog log);
}
