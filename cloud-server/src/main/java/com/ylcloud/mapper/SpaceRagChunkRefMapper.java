package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceRagChunkRef;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 空间 RAG 文本分块引用 Mapper。
 */
@Mapper
public interface SpaceRagChunkRefMapper {

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_rag_chunk_ref(space_id, document_id, space_file_id, file_chunk_id, status, createtime, updatetime) " +
            "values(#{spaceId}, #{documentId}, #{spaceFileId}, #{fileChunkId}, #{status}, #{createtime}, #{updatetime})")
    int insert(SpaceRagChunkRef ref);

    /**
     * 统计 countActiveRef 相关逻辑。
     * @return 影响行数
     */
    @Select("select count(1) from space_rag_chunk_ref where space_id = #{spaceId} and document_id = #{documentId} and file_chunk_id = #{fileChunkId} and status = 1")
    int countActiveRef(@Param("spaceId") Long spaceId,
                       @Param("documentId") Long documentId,
                       @Param("fileChunkId") Long fileChunkId);

    /**
     * 执行 disableByDocumentId 函数的业务处理。
     * @return 影响行数
     */
    @Update("update space_rag_chunk_ref set status = 0, updatetime = #{updateTime} where document_id = #{documentId} and status = 1")
    int disableByDocumentId(@Param("documentId") Long documentId, @Param("updateTime") LocalDateTime updateTime);

    /**
     * 执行 disableBySpaceFileId 函数的业务处理。
     * @return 影响行数
     */
    @Update("update space_rag_chunk_ref set status = 0, updatetime = #{updateTime} where space_id = #{spaceId} and space_file_id = #{spaceFileId} and status = 1")
    int disableBySpaceFileId(@Param("spaceId") Long spaceId,
                             @Param("spaceFileId") Long spaceFileId,
                             @Param("updateTime") LocalDateTime updateTime);
}
