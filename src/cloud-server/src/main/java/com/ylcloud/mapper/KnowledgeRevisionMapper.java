package com.ylcloud.mapper;

import com.ylcloud.entity.*;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KnowledgeRevisionMapper {
    String COLUMNS = "id,space_id as spaceId,space_file_id as spaceFileId,document_id as documentId,"
            + "file_uuid as fileUuid,file_hash as fileHash,file_version_id as fileVersionId,"
            + "minio_version_id as minioVersionId,manifest_hash as manifestHash,published_at as publishedAt,"
            + "superseded_at as supersededAt,retained_until as retainedUntil";

    @Options(useGeneratedKeys=true,keyProperty="id")
    @Insert("insert into knowledge_revision(space_id,space_file_id,document_id,file_uuid,file_hash,file_version_id,"
            + "minio_version_id,manifest_hash,published_at) values(#{spaceId},#{spaceFileId},#{documentId},#{fileUuid},"
            + "#{fileHash},#{fileVersionId},#{minioVersionId},#{manifestHash},#{publishedAt}) "
            + "on duplicate key update id=last_insert_id(id)")
    int insert(KnowledgeRevision revision);

    @Insert("insert ignore into knowledge_revision_chunk(revision_id,chunk_id) values(#{revisionId},#{chunkId})")
    int bindChunk(@Param("revisionId") Long revisionId,@Param("chunkId") Long chunkId);

    @Select("select " + COLUMNS + " from knowledge_revision where space_id=#{spaceId} and space_file_id=#{spaceFileId} "
            + "and file_hash=#{hash} order by published_at desc,id desc limit 1")
    KnowledgeRevision find(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId,@Param("hash") String hash);

    @Select("select " + COLUMNS + " from knowledge_revision where id=#{id} and space_id=#{spaceId} and space_file_id=#{spaceFileId}")
    KnowledgeRevision byId(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId,@Param("id") Long id);

    @Select("select " + COLUMNS + " from knowledge_revision where space_id=#{spaceId} and space_file_id=#{spaceFileId} "
            + "and superseded_at is null order by published_at desc,id desc limit 1")
    KnowledgeRevision current(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId);

    @Select("select " + COLUMNS + " from knowledge_revision where space_id=#{spaceId} and space_file_id=#{spaceFileId} "
            + "and superseded_at is null order by published_at desc,id desc limit 1 for update")
    KnowledgeRevision currentForUpdate(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId);

    @Select("select " + COLUMNS + " from knowledge_revision where space_id=#{spaceId} and id in "
            + "(select revision_id from knowledge_revision_chunk where chunk_id=#{chunkId}) order by published_at desc,id desc")
    List<KnowledgeRevision> forChunk(@Param("spaceId") Long spaceId,@Param("chunkId") Long chunkId);

    @Select("select " + COLUMNS + " from knowledge_revision where space_id=#{spaceId} order by id desc")
    List<KnowledgeRevision> listBySpace(@Param("spaceId") Long spaceId);

    @Select("select c.id,c.file_uuid as fileUuid,c.file_hash as fileHash,c.chunk_index as chunkIndex,c.content,"
            + "c.content_hash as contentHash,c.token_count as tokenCount,c.metadata,c.vector_id as vectorId,"
            + "c.embedding_model as embeddingModel,c.chunk_size as chunkSize,c.chunk_overlap as chunkOverlap,"
            + "c.status,c.createtime,c.updatetime from file_rag_chunk c join knowledge_revision_chunk r on r.chunk_id=c.id "
            + "where r.revision_id=#{revisionId} order by c.chunk_index,c.id")
    List<FileRagChunk> chunks(@Param("revisionId") Long revisionId);

    @Select("select c.id,c.file_uuid as fileUuid,c.file_hash as fileHash,c.chunk_index as chunkIndex,c.content,"
            + "c.content_hash as contentHash,c.token_count as tokenCount,c.metadata,c.vector_id as vectorId,"
            + "c.embedding_model as embeddingModel,c.chunk_size as chunkSize,c.chunk_overlap as chunkOverlap,"
            + "c.status,c.createtime,c.updatetime from file_rag_chunk c join space_rag_chunk_ref r on r.file_chunk_id=c.id "
            + "where r.document_id=#{documentId} and r.status=1 and c.status=1 order by c.chunk_index,c.id")
    List<FileRagChunk> activeDocumentChunks(@Param("documentId") Long documentId);

    @Select("select c.id,c.file_uuid as fileUuid,c.file_hash as fileHash,c.chunk_index as chunkIndex,c.content,"
            + "c.content_hash as contentHash,c.token_count as tokenCount,c.metadata,c.vector_id as vectorId,"
            + "c.embedding_model as embeddingModel,c.chunk_size as chunkSize,c.chunk_overlap as chunkOverlap,"
            + "c.status,c.createtime,c.updatetime from file_rag_chunk c join space_rag_chunk_ref r on r.file_chunk_id=c.id "
            + "where r.document_id=#{documentId} and r.status=1 and c.status=1 order by c.chunk_index,c.id for update")
    List<FileRagChunk> activeDocumentChunksForUpdate(@Param("documentId") Long documentId);

    @Update("update knowledge_revision set superseded_at=#{now},retained_until=#{retainedUntil} "
            + "where space_id=#{spaceId} and space_file_id=#{spaceFileId} and superseded_at is null")
    int supersede(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId,
                  @Param("now") LocalDateTime now,@Param("retainedUntil") LocalDateTime retainedUntil);

    @Update("update knowledge_revision set superseded_at=null,retained_until=null where id=#{id}")
    int publish(@Param("id") Long id);

    @Update("update space_rag_document set consistency_version=consistency_version+1,consistency_async_task_id=null,"
            + "index_status='PENDING',vector_state='CLEAN',chunk_count=0,error_message=null,updatetime=#{now} "
            + "where space_id=#{spaceId} and space_file_id=#{spaceFileId} and status=1")
    int invalidateIndex(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId,@Param("now") LocalDateTime now);

    @Update("update space_rag_chunk_ref set status=0,updatetime=#{now} where space_id=#{spaceId} and space_file_id=#{spaceFileId}")
    int invalidateRefs(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId,@Param("now") LocalDateTime now);

    @Update("update space_file set file_uuid=#{newUuid},node_version=node_version+1,updatetime=#{now} "
            + "where space_id=#{spaceId} and id=#{spaceFileId} and file_uuid=#{oldUuid} and status=1 and lifecycle_state='ACTIVE'")
    int rebindPhysical(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId,
                       @Param("oldUuid") String oldUuid,@Param("newUuid") String newUuid,@Param("now") LocalDateTime now);

    @Update("update physical_file_reference set file_uuid=#{newUuid} where reference_type='SPACE_FILE' "
            + "and reference_id=#{spaceFileId} and file_uuid=#{oldUuid} and active=1")
    int rebindPhysicalReference(@Param("spaceFileId") Long spaceFileId,@Param("oldUuid") String oldUuid,
                                @Param("newUuid") String newUuid);

    @Update("update space_rag_document set file_uuid=#{newUuid} where space_id=#{spaceId} and space_file_id=#{spaceFileId} and status=1")
    int rebindDocumentPhysical(@Param("spaceId") Long spaceId,@Param("spaceFileId") Long spaceFileId,@Param("newUuid") String newUuid);

    @Select("select count(*) from knowledge_revision kr join space_file sf on sf.id=kr.space_file_id and sf.space_id=kr.space_id "
            + "join space_rag_config cfg on cfg.space_id=kr.space_id where kr.file_uuid=#{fileUuid} "
            + "and sf.status=1 and sf.lifecycle_state='ACTIVE' and cfg.status=1 "
            + "and (kr.superseded_at is null or (cfg.historical_evidence_enabled=1 and cfg.history_retention_days>0 "
            + "and date_add(kr.superseded_at,interval cfg.history_retention_days day)>#{now}) "
            + "or (cfg.allow_stale_during_reindex=1 and not exists(select 1 from knowledge_revision n "
            + "where n.space_id=kr.space_id and n.space_file_id=kr.space_file_id and n.superseded_at is null)))")
    int activePhysicalRights(@Param("fileUuid") String fileUuid,@Param("now") LocalDateTime now);

    @Select("select count(*) from knowledge_revision where file_uuid=#{fileUuid}")
    int snapshotCount(@Param("fileUuid") String fileUuid);
}
