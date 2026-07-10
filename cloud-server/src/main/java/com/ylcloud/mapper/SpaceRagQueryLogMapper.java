package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceRagQueryLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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

    @Select("select id, space_id as spaceId, user_id as userId, question, answer, hit_chunk_ids as hitChunkIds, model_name as modelName, " +
            "top_k as topK, temperature, prompt_tokens as promptTokens, completion_tokens as completionTokens, total_tokens as totalTokens, " +
            "success, error_message as errorMessage, createtime " +
            "from space_rag_query_log where space_id = #{spaceId} order by createtime desc limit #{limit}")
    List<SpaceRagQueryLog> listRecent(@Param("spaceId") Long spaceId, @Param("limit") Integer limit);

    @Select("select id, space_id as spaceId, user_id as userId, question, answer, hit_chunk_ids as hitChunkIds, model_name as modelName, " +
            "top_k as topK, temperature, prompt_tokens as promptTokens, completion_tokens as completionTokens, total_tokens as totalTokens, " +
            "success, error_message as errorMessage, createtime " +
            "from space_rag_query_log " +
            "where space_id = #{spaceId} and (success = 0 or answer like '%无法从当前知识库回答%' or hit_chunk_ids is null or hit_chunk_ids = '') " +
            "order by createtime desc limit #{limit}")
    List<SpaceRagQueryLog> listNoAnswer(@Param("spaceId") Long spaceId, @Param("limit") Integer limit);

    @Select("select count(1) from space_rag_query_log where space_id = #{spaceId}")
    Integer countBySpaceId(@Param("spaceId") Long spaceId);

    @Select("select count(1) from space_rag_query_log where space_id = #{spaceId} and success = 1")
    Integer countSuccessBySpaceId(@Param("spaceId") Long spaceId);

    @Select("select count(1) from space_rag_query_log where space_id = #{spaceId} and success = 0")
    Integer countFailedBySpaceId(@Param("spaceId") Long spaceId);

    @Select("select count(1) from space_rag_query_log " +
            "where space_id = #{spaceId} and (success = 0 or answer like '%无法从当前知识库回答%' or hit_chunk_ids is null or hit_chunk_ids = '')")
    Integer countNoAnswerBySpaceId(@Param("spaceId") Long spaceId);

    @Select("select count(1) from space_rag_query_log where space_id = #{spaceId} and hit_chunk_ids is not null and hit_chunk_ids <> ''")
    Integer countCitedBySpaceId(@Param("spaceId") Long spaceId);
}
