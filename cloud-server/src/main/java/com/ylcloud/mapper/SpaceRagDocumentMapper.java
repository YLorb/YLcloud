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

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_rag_document(space_id, space_file_id, file_uuid, file_name, file_hash, file_type, index_status, vector_state, chunk_count, error_message, created_by, status, createtime, updatetime) " +
            "values(#{spaceId}, #{spaceFileId}, #{fileUuid}, #{fileName}, #{fileHash}, #{fileType}, #{indexStatus}, #{vectorState}, #{chunkCount}, #{errorMessage}, #{createdBy}, #{status}, #{createtime}, #{updatetime})")
    int insert(SpaceRagDocument document);

    /**
     * 查询 getBySpaceFileId 相关逻辑。
     * @return 处理结果
     */
    @Select("select d.id, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, d.file_name as fileName, " +
            "d.file_hash as fileHash, d.file_type as fileType, d.index_status as indexStatus, d.vector_state as vectorState, d.consistency_version as consistencyVersion, d.consistency_async_task_id as consistencyAsyncTaskId, d.chunk_count as chunkCount, " +
            "d.error_message as errorMessage, d.created_by as createdBy, d.status, d.createtime, d.updatetime " +
            "from space_rag_document d where d.space_id = #{spaceId} and d.space_file_id = #{spaceFileId} and d.status = 1")
    SpaceRagDocument getBySpaceFileId(@Param("spaceId") Long spaceId, @Param("spaceFileId") Long spaceFileId);

    /**
     * 查询 getById 相关逻辑。
     * @return 处理结果
     */
    @Select("select d.id, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, d.file_name as fileName, " +
            "d.file_hash as fileHash, d.file_type as fileType, d.index_status as indexStatus, d.vector_state as vectorState, d.consistency_version as consistencyVersion, d.consistency_async_task_id as consistencyAsyncTaskId, d.chunk_count as chunkCount, " +
            "d.error_message as errorMessage, d.created_by as createdBy, d.status, d.createtime, d.updatetime " +
            "from space_rag_document d where d.id = #{id} and d.status = 1")
    SpaceRagDocument getById(@Param("id") Long id);

    @Select("select d.id, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, d.file_name as fileName, " +
            "d.file_hash as fileHash, d.file_type as fileType, d.index_status as indexStatus, d.vector_state as vectorState, d.consistency_version as consistencyVersion, d.consistency_async_task_id as consistencyAsyncTaskId, d.chunk_count as chunkCount, " +
            "d.error_message as errorMessage, d.created_by as createdBy, d.status, d.createtime, d.updatetime " +
            "from space_rag_document d where d.id=#{id} for update")
    SpaceRagDocument getAnyByIdForUpdate(@Param("id") Long id);

    @Select("select d.id, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, d.file_name as fileName, " +
            "d.file_hash as fileHash, d.file_type as fileType, d.index_status as indexStatus, d.vector_state as vectorState, d.consistency_version as consistencyVersion, d.consistency_async_task_id as consistencyAsyncTaskId, d.chunk_count as chunkCount, " +
            "d.error_message as errorMessage, d.created_by as createdBy, d.status, d.createtime, d.updatetime " +
            "from space_rag_document d where d.id = #{id}")
    SpaceRagDocument getAnyById(@Param("id") Long id);

    /**
     * 查询 getBySpaceAndChunkId 相关逻辑。
     * @return 处理结果
     */
    @Select("select d.id, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, d.file_name as fileName, " +
            "d.file_hash as fileHash, d.file_type as fileType, d.index_status as indexStatus, d.vector_state as vectorState, d.chunk_count as chunkCount, " +
            "d.error_message as errorMessage, d.created_by as createdBy, d.status, d.createtime, d.updatetime " +
            "from space_rag_document d join space_rag_chunk_ref r on r.document_id = d.id " +
            "join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "where r.space_id = #{spaceId} and r.file_chunk_id = #{chunkId} and r.status = 1 " +
            "and d.status = 1 and d.index_status = 'SUCCESS' and d.vector_state = 'ACTIVE' and sf.status = 1 and sf.lifecycle_state='ACTIVE' and sf.searchable = 1 limit 1")
    SpaceRagDocument getBySpaceAndChunkId(@Param("spaceId") Long spaceId, @Param("chunkId") Long chunkId);

    /**
     * 查询 listBySpaceId 相关逻辑。
     * @return 列表结果
     */
    @Select("select d.id, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, d.file_name as fileName, " +
            "d.file_hash as fileHash, d.file_type as fileType, d.index_status as indexStatus, d.vector_state as vectorState, d.consistency_version as consistencyVersion, d.consistency_async_task_id as consistencyAsyncTaskId, d.chunk_count as chunkCount, " +
            "d.error_message as errorMessage, d.created_by as createdBy, d.status, d.createtime, d.updatetime " +
            "from space_rag_document d join space_file sf on sf.id=d.space_file_id and sf.space_id=d.space_id " +
            "where d.space_id = #{spaceId} and d.status = 1 and sf.status=1 and sf.lifecycle_state='ACTIVE' order by d.updatetime desc")
    List<SpaceRagDocument> listBySpaceId(@Param("spaceId") Long spaceId);

    @Select("select d.id, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, d.file_name as fileName, " +
            "d.file_hash as fileHash, d.file_type as fileType, d.index_status as indexStatus, d.vector_state as vectorState, d.consistency_version as consistencyVersion, d.consistency_async_task_id as consistencyAsyncTaskId, d.chunk_count as chunkCount, " +
            "d.error_message as errorMessage, d.created_by as createdBy, d.status, d.createtime, d.updatetime " +
            "from space_rag_document d join space_file sf on sf.id=d.space_file_id and sf.space_id=d.space_id " +
            "where d.space_id=#{spaceId} and d.status=1 and sf.status=1 and sf.lifecycle_state='ACTIVE' and d.id > #{afterId} order by d.id limit #{limit}")
    List<SpaceRagDocument> listBySpaceIdAfterId(@Param("spaceId") Long spaceId,
                                                @Param("afterId") Long afterId,
                                                @Param("limit") Integer limit);

    /**
     * 更新 updateIndexResult 相关逻辑。
     * @return 影响行数
     */
    @Update("update space_rag_document set index_status = #{indexStatus}, consistency_version=consistency_version+1, consistency_async_task_id=null, " +
            "vector_state = case when #{indexStatus} = 'SUCCESS' then 'ACTIVE' when #{indexStatus} = 'INDEXING' then 'BUILDING' else 'CLEANUP_PENDING' end, " +
            "chunk_count = #{chunkCount}, error_message = #{errorMessage}, updatetime = #{updateTime} where id = #{id} and status = 1")
    int updateIndexResult(@Param("id") Long id,
                          @Param("indexStatus") String indexStatus,
                          @Param("chunkCount") Integer chunkCount,
                          @Param("errorMessage") String errorMessage,
                          @Param("updateTime") LocalDateTime updateTime);

    /**
     * 更新 updateFileMeta 相关逻辑。
     * @return 影响行数
     */
    @Update("update space_rag_document set file_name = #{fileName}, file_hash = #{fileHash}, file_type = #{fileType}, consistency_version=consistency_version+1, consistency_async_task_id=null, updatetime = #{updateTime} " +
            "where id = #{id} and status = 1")
    int updateFileMeta(@Param("id") Long id,
                       @Param("fileName") String fileName,
                       @Param("fileHash") String fileHash,
                       @Param("fileType") String fileType,
                       @Param("updateTime") LocalDateTime updateTime);

    /**
     * 执行 disableBySpaceFileId 函数的业务处理。
     * @return 影响行数
     */
    @Update("update space_rag_document set status = 0, index_status = #{indexStatus}, vector_state = 'CLEANUP_PENDING', consistency_version=consistency_version+1, consistency_async_task_id=null, error_message = #{errorMessage}, updatetime = #{updateTime} " +
            "where space_id = #{spaceId} and space_file_id = #{spaceFileId} and status = 1")
    int disableBySpaceFileId(@Param("spaceId") Long spaceId,
                             @Param("spaceFileId") Long spaceFileId,
                             @Param("indexStatus") String indexStatus,
                             @Param("errorMessage") String errorMessage,
                             @Param("updateTime") LocalDateTime updateTime);

    /**
     * 搜索 searchDocuments 相关逻辑。
     * @return 列表结果
     */
    @Select("select d.id as documentId, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, " +
            "d.file_name as fileName, d.file_type as fileType, sf.path as path, d.index_status as indexStatus, " +
            "d.chunk_count as chunkCount, d.updatetime as updatetime " +
            "from space_rag_document d join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "where d.space_id = #{spaceId} and d.status = 1 and sf.status = 1 and sf.lifecycle_state='ACTIVE' " +
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

    /**
     * 查询 listSearchDocumentsByIds 相关逻辑。
     * @return 列表结果
     */
    @Select("<script>" +
            "select d.id as documentId, d.space_id as spaceId, d.space_file_id as spaceFileId, d.file_uuid as fileUuid, " +
            "d.file_name as fileName, d.file_type as fileType, sf.path as path, d.index_status as indexStatus, " +
            "d.chunk_count as chunkCount, d.updatetime as updatetime " +
            "from space_rag_document d join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "where d.space_id = #{spaceId} and d.status = 1 and sf.status = 1 and sf.lifecycle_state='ACTIVE' " +
            "and d.id in " +
            "<foreach collection='documentIds' item='documentId' open='(' separator=',' close=')'>#{documentId}</foreach>" +
            "</script>")
    List<SpaceDocumentSearchVO> listSearchDocumentsByIds(@Param("spaceId") Long spaceId,
                                                         @Param("documentIds") List<Long> documentIds);

    /**
     * 将空间中超时仍处于索引中的文档标记为失败。
     * @return 影响行数
     */
    @Update("update space_rag_document set index_status = 'FAILED', vector_state = 'CLEANUP_PENDING', consistency_version=consistency_version+1, consistency_async_task_id=null, chunk_count = 0, error_message = #{errorMessage}, updatetime = #{updateTime} " +
            "where space_id = #{spaceId} and status = 1 and index_status = 'INDEXING' and updatetime <= #{cutoff}")
    int failStaleIndexingDocuments(@Param("spaceId") Long spaceId,
                                   @Param("cutoff") LocalDateTime cutoff,
                                   @Param("errorMessage") String errorMessage,
                                   @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document d join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "set d.index_status = 'INDEXING', d.vector_state = 'BUILDING', d.consistency_version=d.consistency_version+1, d.consistency_async_task_id=null, d.chunk_count = 0, d.error_message = null, d.updatetime = #{updateTime} " +
            "where d.id = #{id} and d.status = 1 and sf.status = 1 and sf.lifecycle_state='ACTIVE' and d.vector_state in ('CLEAN', 'ACTIVE')")
    int beginIndex(@Param("id") Long id, @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document d join space_file sf on sf.id=d.space_file_id and sf.space_id=d.space_id " +
            "set d.index_status=case when d.index_status='SUCCESS' then 'SUCCESS' else 'INDEXING' end, " +
            "d.vector_state='BUILDING',d.consistency_async_task_id=#{asyncTaskId},d.error_message=null,d.updatetime=#{updateTime} " +
            "where d.id=#{id} and d.status=1 and sf.status=1 and sf.lifecycle_state='ACTIVE' and d.consistency_version=#{version} " +
            "and d.vector_state in ('CLEAN','ACTIVE','BUILDING','CLEANUP_PENDING','CLEANING') " +
            "and (d.consistency_async_task_id is null or d.consistency_async_task_id=#{asyncTaskId} " +
            "or d.vector_state in ('CLEANUP_PENDING','CLEANING'))")
    int beginIndexFenced(@Param("id") Long id,@Param("version") Long version,
                         @Param("asyncTaskId") Long asyncTaskId,@Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document d join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "set d.index_status = 'SUCCESS', d.vector_state = 'ACTIVE', d.consistency_version=d.consistency_version+1, d.consistency_async_task_id=null, d.chunk_count = #{chunkCount}, d.error_message = null, d.updatetime = #{updateTime} " +
            "where d.id = #{id} and d.status = 1 and sf.status = 1 and sf.lifecycle_state='ACTIVE' and d.index_status = 'INDEXING' and d.vector_state = 'BUILDING'")
    int commitIndex(@Param("id") Long id,
                    @Param("chunkCount") Integer chunkCount,
                    @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document d join space_file sf on sf.id=d.space_file_id and sf.space_id=d.space_id " +
            "set d.index_status='SUCCESS',d.vector_state='ACTIVE',d.consistency_version=d.consistency_version+1, " +
            "d.consistency_async_task_id=null,d.chunk_count=#{chunkCount},d.error_message=null,d.updatetime=#{updateTime} " +
            "where d.id=#{id} and d.status=1 and sf.status=1 and sf.lifecycle_state='ACTIVE' and d.consistency_version=#{version} " +
            "and d.consistency_async_task_id=#{asyncTaskId} and d.vector_state='BUILDING'")
    int commitIndexFenced(@Param("id") Long id,@Param("chunkCount") Integer chunkCount,
                          @Param("version") Long version,@Param("asyncTaskId") Long asyncTaskId,
                          @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document set " +
            "index_status=case when index_status='SUCCESS' then 'SUCCESS' else 'FAILED' end, " +
            "vector_state=case when index_status='SUCCESS' then 'ACTIVE' else 'CLEANUP_PENDING' end, " +
            "consistency_version=consistency_version+1,consistency_async_task_id=null, " +
            "chunk_count=case when index_status='SUCCESS' then chunk_count else 0 end,error_message=#{errorMessage},updatetime=#{updateTime} " +
            "where id=#{id} and consistency_version=#{version} and consistency_async_task_id=#{asyncTaskId} and vector_state='BUILDING'")
    int failIndexFenced(@Param("id") Long id,@Param("version") Long version,
                        @Param("asyncTaskId") Long asyncTaskId,@Param("errorMessage") String errorMessage,
                        @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document set index_status = 'FAILED', vector_state = 'CLEANUP_PENDING', consistency_version=consistency_version+1, consistency_async_task_id=null, chunk_count = 0, " +
            "error_message = #{errorMessage}, updatetime = #{updateTime} " +
            "where id = #{id} and status = 1 and index_status = 'INDEXING' and vector_state = 'BUILDING'")
    int failIfBuilding(@Param("id") Long id,
                       @Param("errorMessage") String errorMessage,
                       @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document set index_status = 'FAILED', vector_state = 'CLEANUP_PENDING', consistency_version=consistency_version+1, consistency_async_task_id=null, chunk_count = 0, " +
            "error_message = #{errorMessage}, updatetime = #{updateTime} " +
            "where id = #{id} and status = 1 and index_status = 'SUCCESS' and vector_state = 'ACTIVE'")
    int failIfActive(@Param("id") Long id,
                     @Param("errorMessage") String errorMessage,
                     @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document set vector_state = 'CLEANING', updatetime = #{updateTime} " +
            "where id = #{id} and vector_state = 'CLEANUP_PENDING' and index_status <> 'SUCCESS'")
    int claimCleanup(@Param("id") Long id, @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document set vector_state = 'CLEAN', consistency_version=consistency_version+1, consistency_async_task_id=null, updatetime = #{updateTime} " +
            "where id = #{id} and vector_state = 'CLEANING' and index_status <> 'SUCCESS'")
    int completeCleanup(@Param("id") Long id, @Param("updateTime") LocalDateTime updateTime);

    @Update("update space_rag_document set vector_state = 'CLEANUP_PENDING', updatetime = #{updateTime} " +
            "where id = #{id} and vector_state = 'CLEANING' and index_status <> 'SUCCESS'")
    int releaseCleanup(@Param("id") Long id, @Param("updateTime") LocalDateTime updateTime);

    @Select("select id, space_id as spaceId, space_file_id as spaceFileId, file_uuid as fileUuid, file_name as fileName, " +
            "file_hash as fileHash, file_type as fileType, index_status as indexStatus, vector_state as vectorState, consistency_version as consistencyVersion, consistency_async_task_id as consistencyAsyncTaskId, chunk_count as chunkCount, " +
            "error_message as errorMessage, created_by as createdBy, status, createtime, updatetime " +
            "from space_rag_document where vector_state = 'CLEANUP_PENDING' order by updatetime asc limit #{limit}")
    List<SpaceRagDocument> listCleanupPending(@Param("limit") Integer limit);

    @Select("select id, space_id as spaceId, space_file_id as spaceFileId, file_uuid as fileUuid, file_name as fileName, " +
            "file_hash as fileHash, file_type as fileType, index_status as indexStatus, vector_state as vectorState, consistency_version as consistencyVersion, consistency_async_task_id as consistencyAsyncTaskId, chunk_count as chunkCount, " +
            "error_message as errorMessage, created_by as createdBy, status, createtime, updatetime " +
            "from space_rag_document where status = 1 and index_status = 'SUCCESS' and vector_state = 'ACTIVE' " +
            "order by updatetime asc")
    List<SpaceRagDocument> listActiveVectorDocuments();

    @Update("update space_rag_document set consistency_async_task_id=#{asyncTaskId} where id=#{id} " +
            "and consistency_version=#{version} and (consistency_async_task_id is null or consistency_async_task_id=#{asyncTaskId})")
    int bindConsistencyTask(@Param("id") Long id,@Param("version") Long version,@Param("asyncTaskId") Long asyncTaskId);

    @Update("update space_rag_document set consistency_version=consistency_version+1,consistency_async_task_id=null " +
            "where id=#{id} and consistency_version=#{version} and consistency_async_task_id=#{asyncTaskId} " +
            "and status=1 and index_status='SUCCESS' and vector_state='ACTIVE'")
    int completeConsistencyValidation(@Param("id") Long id,@Param("version") Long version,
                                      @Param("asyncTaskId") Long asyncTaskId);

    @Update("update space_rag_document set file_uuid = concat('deleted-', id), file_name = '', file_hash = null, file_type = null, " +
            "index_status = 'FAILED', vector_state = 'CLEAN', consistency_version = consistency_version + 1, " +
            "consistency_async_task_id = null, chunk_count = 0, error_message = 'Account purged', status = 0, " +
            "updatetime = #{now} where space_id = #{spaceId}")
    int purgeBySpaceId(@Param("spaceId") Long spaceId, @Param("now") LocalDateTime now);

}
