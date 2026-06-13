package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceRagDocument;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import com.ylcloud.VO.SpaceDocumentSearchVO;

/**
 * 空间 RAG 文档索引 Mapper。
 */
@Mapper
public interface SpaceRagDocumentMapper {

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_rag_document(space_id, space_file_id, file_uuid, file_name, file_hash, file_type, index_status, chunk_count, error_message, created_by, status, createtime, updatetime) " +
            "values(#{spaceId}, #{spaceFileId}, #{fileUuid}, #{fileName}, #{fileHash}, #{fileType}, #{indexStatus}, #{chunkCount}, #{errorMessage}, #{createdBy}, #{status}, #{createtime}, #{updatetime})")
    int insert(SpaceRagDocument document);

    @Select("select id, space_id as spaceId, space_file_id as spaceFileId, file_uuid as fileUuid, file_name as fileName, " +
            "file_hash as fileHash, file_type as fileType, index_status as indexStatus, chunk_count as chunkCount, " +
            "error_message as errorMessage, created_by as createdBy, status, createtime, updatetime " +
            "from space_rag_document where space_id = #{spaceId} and space_file_id = #{spaceFileId} and status = 1")
    SpaceRagDocument getBySpaceFileId(@Param("spaceId") Long spaceId, @Param("spaceFileId") Long spaceFileId);

    @Select("select id, space_id as spaceId, space_file_id as spaceFileId, file_uuid as fileUuid, file_name as fileName, " +
            "file_hash as fileHash, file_type as fileType, index_status as indexStatus, chunk_count as chunkCount, " +
            "error_message as errorMessage, created_by as createdBy, status, createtime, updatetime " +
            "from space_rag_document where id = #{id} and status = 1")
    SpaceRagDocument getById(@Param("id") Long id);

    @Select("select id, space_id as spaceId, space_file_id as spaceFileId, file_uuid as fileUuid, file_name as fileName, " +
            "file_hash as fileHash, file_type as fileType, index_status as indexStatus, chunk_count as chunkCount, " +
            "error_message as errorMessage, created_by as createdBy, status, createtime, updatetime " +
            "from space_rag_document where space_id = #{spaceId} and status = 1 order by updatetime desc")
    List<SpaceRagDocument> listBySpaceId(@Param("spaceId") Long spaceId);

    @Update("update space_rag_document set index_status = #{indexStatus}, chunk_count = #{chunkCount}, " +
            "error_message = #{errorMessage}, updatetime = #{updateTime} where id = #{id} and status = 1")
    int updateIndexResult(@Param("id") Long id,
                          @Param("indexStatus") String indexStatus,
                          @Param("chunkCount") Integer chunkCount,
                          @Param("errorMessage") String errorMessage,
                          @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document set file_name = #{fileName}, file_hash = #{fileHash}, file_type = #{fileType}, updatetime = #{updateTime} " +
            "where id = #{id} and status = 1")
    int updateFileMeta(@Param("id") Long id,
                       @Param("fileName") String fileName,
                       @Param("fileHash") String fileHash,
                       @Param("fileType") String fileType,
                       @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document set status = 0, index_status = #{indexStatus}, error_message = #{errorMessage}, updatetime = #{updateTime} " +
            "where space_id = #{spaceId} and space_file_id = #{spaceFileId} and status = 1")
    int disableBySpaceFileId(@Param("spaceId") Long spaceId,
                             @Param("spaceFileId") Long spaceFileId,
                             @Param("indexStatus") String indexStatus,
                             @Param("errorMessage") String errorMessage,
                             @Param("updateTime") LocalDateTime updateTime);

    @Select("select d.id as documentId, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, " +
            "d.file_name as fileName, d.file_type as fileType, sf.path as path, d.index_status as indexStatus, " +
            "d.chunk_count as chunkCount, d.updatetime as updatetime " +
            "from space_rag_document d join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "where d.space_id = #{spaceId} and d.status = 1 and sf.status = 1 " +
            "and (#{keyword} is null or #{keyword} = '' or d.file_name like concat('%', #{keyword}, '%') or sf.path like concat('%', #{keyword}, '%')) " +
            "and (#{fileType} is null or #{fileType} = '' or d.file_type = #{fileType}) " +
            "and (#{indexStatus} is null or #{indexStatus} = '' or d.index_status = #{indexStatus}) " +
            "order by d.updatetime desc limit #{limit} offset #{offset}")
    List<SpaceDocumentSearchVO> searchDocuments(@Param("spaceId") Long spaceId,
                                                @Param("keyword") String keyword,
                                                @Param("fileType") String fileType,
                                                @Param("indexStatus") String indexStatus,
                                                @Param("limit") Integer limit,
                                                @Param("offset") Integer offset);

    @Select("<script>" +
            "select d.id as documentId, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, " +
            "d.file_name as fileName, d.file_type as fileType, sf.path as path, d.index_status as indexStatus, " +
            "d.chunk_count as chunkCount, d.updatetime as updatetime " +
            "from space_rag_document d join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "where d.space_id = #{spaceId} and d.status = 1 and sf.status = 1 " +
            "and d.id in " +
            "<foreach collection='documentIds' item='documentId' open='(' separator=',' close=')'>#{documentId}</foreach>" +
            "</script>")
    List<SpaceDocumentSearchVO> listSearchDocumentsByIds(@Param("spaceId") Long spaceId,
                                                         @Param("documentIds") List<Long> documentIds);
}
