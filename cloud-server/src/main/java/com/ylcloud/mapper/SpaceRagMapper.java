package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceRagConfig;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 空间 RAG 配置 Mapper。
 */
@Mapper
public interface SpaceRagMapper {

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_rag_config(space_id, embedding_model, chat_model, vector_collection, chunk_size, chunk_overlap, top_k, temperature, score_threshold, enabled, status, createtime, updatetime) " +
            "values(#{spaceId}, #{embeddingModel}, #{chatModel}, #{vectorCollection}, #{chunkSize}, #{chunkOverlap}, #{topK}, #{temperature}, #{scoreThreshold}, #{enabled}, #{status}, #{createtime}, #{updatetime})")
    int insert(SpaceRagConfig config);

    /**
     * 查询 getBySpaceId 相关逻辑。
     * @return 处理结果
     */
    @Select("select id, space_id as spaceId, embedding_model as embeddingModel, chat_model as chatModel, vector_collection as vectorCollection, " +
            "chunk_size as chunkSize, chunk_overlap as chunkOverlap, top_k as topK, temperature, score_threshold as scoreThreshold, enabled, status, createtime, updatetime " +
            "from space_rag_config where space_id = #{spaceId} and status = 1")
    SpaceRagConfig getBySpaceId(@Param("spaceId") Long spaceId);

    /**
     * 更新 updateConfig 相关逻辑。
     * @return 影响行数
     */
    @Update("update space_rag_config set embedding_model = #{embeddingModel}, chat_model = #{chatModel}, " +
            "chunk_size = #{chunkSize}, chunk_overlap = #{chunkOverlap}, top_k = #{topK}, temperature = #{temperature}, " +
            "score_threshold = #{scoreThreshold}, enabled = #{enabled}, updatetime = #{updateTime} " +
            "where space_id = #{spaceId} and status = 1")
    int updateConfig(@Param("spaceId") Long spaceId,
                     @Param("embeddingModel") String embeddingModel,
                     @Param("chatModel") String chatModel,
                     @Param("chunkSize") Integer chunkSize,
                     @Param("chunkOverlap") Integer chunkOverlap,
                     @Param("topK") Integer topK,
                     @Param("temperature") BigDecimal temperature,
                     @Param("scoreThreshold") BigDecimal scoreThreshold,
                     @Param("enabled") Integer enabled,
                     @Param("updateTime") LocalDateTime updateTime);
}
