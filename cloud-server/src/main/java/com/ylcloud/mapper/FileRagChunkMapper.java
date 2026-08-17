package com.ylcloud.mapper;

import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.VO.SpaceDocumentChunkHitVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 物理文件 RAG 文本分块 Mapper。
 */
@Mapper
public interface FileRagChunkMapper {

    /**
     * 新增 insert 相关逻辑。
     * @return 影响行数
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into file_rag_chunk(file_uuid, file_hash, chunk_index, content, content_hash, token_count, metadata, vector_id, embedding_model, chunk_size, chunk_overlap, status, createtime, updatetime) " +
            "values(#{fileUuid}, #{fileHash}, #{chunkIndex}, #{content}, #{contentHash}, #{tokenCount}, #{metadata}, #{vectorId}, #{embeddingModel}, #{chunkSize}, #{chunkOverlap}, #{status}, #{createtime}, #{updatetime}) " +
            "on duplicate key update id = last_insert_id(id), content = values(content), content_hash = values(content_hash), " +
            "token_count = values(token_count), metadata = values(metadata), vector_id = values(vector_id), " +
            "embedding_model = values(embedding_model), chunk_size = values(chunk_size), chunk_overlap = values(chunk_overlap), " +
            "status = values(status), updatetime = values(updatetime)")
    int insert(FileRagChunk chunk);

    /**
     * 查询 listByFileUuid 相关逻辑。
     * @return 列表结果
     */
    @Select("select id, file_uuid as fileUuid, file_hash as fileHash, chunk_index as chunkIndex, content, content_hash as contentHash, " +
            "token_count as tokenCount, metadata, vector_id as vectorId, embedding_model as embeddingModel, chunk_size as chunkSize, " +
            "chunk_overlap as chunkOverlap, status, createtime, updatetime from file_rag_chunk " +
            "where file_uuid = #{fileUuid} and status = 1 order by chunk_index")
    List<FileRagChunk> listByFileUuid(@Param("fileUuid") String fileUuid);

    /**
     * 查询 listByFileUuidAndHash 相关逻辑。
     * @return 列表结果
     */
    @Select("select id, file_uuid as fileUuid, file_hash as fileHash, chunk_index as chunkIndex, content, content_hash as contentHash, " +
            "token_count as tokenCount, metadata, vector_id as vectorId, embedding_model as embeddingModel, chunk_size as chunkSize, " +
            "chunk_overlap as chunkOverlap, status, createtime, updatetime from file_rag_chunk " +
            "where file_uuid = #{fileUuid} and file_hash = #{fileHash} and status = 1 order by chunk_index")
    List<FileRagChunk> listByFileUuidAndHash(@Param("fileUuid") String fileUuid, @Param("fileHash") String fileHash);

    /**
     * 禁用指定物理文件版本的 active chunks，用于清理旧的 metadata fallback 缓存。
     * @return 影响行数
     */
    @Update("update file_rag_chunk set status = 0, updatetime = now() " +
            "where file_uuid = #{fileUuid} and file_hash = #{fileHash} and status = 1")
    int disableByFileUuidAndHash(@Param("fileUuid") String fileUuid, @Param("fileHash") String fileHash);

    @Update("update file_rag_chunk set status = 0, updatetime = now() where file_uuid = #{fileUuid} and status = 1")
    int disableByFileUuid(@Param("fileUuid") String fileUuid);

    /**
     * 搜索 searchBySpaceAndKeyword 相关逻辑。
     * @return 列表结果
     */
    @Select("select c.id, c.file_uuid as fileUuid, c.file_hash as fileHash, c.chunk_index as chunkIndex, c.content, " +
            "c.content_hash as contentHash, c.token_count as tokenCount, c.metadata, c.vector_id as vectorId, " +
            "c.embedding_model as embeddingModel, c.chunk_size as chunkSize, c.chunk_overlap as chunkOverlap, " +
            "c.status, c.createtime, c.updatetime from file_rag_chunk c " +
            "join space_rag_chunk_ref r on r.file_chunk_id = c.id " +
            "join space_rag_document d on d.id = r.document_id " +
            "join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "where r.space_id = #{spaceId} and r.status = 1 and c.status = 1 " +
            "and d.status = 1 and d.index_status = 'SUCCESS' and d.vector_state = 'ACTIVE' and sf.status = 1 and sf.lifecycle_state='ACTIVE' and sf.searchable = 1 " +
            "and c.content like concat('%', #{keyword}, '%') order by c.updatetime desc limit #{limit}")
    List<FileRagChunk> searchBySpaceAndKeyword(@Param("spaceId") Long spaceId,
                                               @Param("keyword") String keyword,
                                               @Param("limit") Integer limit);

    /**
     * Search chunks by structural metadata and document metadata.
     */
    @Select("select c.id, c.file_uuid as fileUuid, c.file_hash as fileHash, c.chunk_index as chunkIndex, c.content, " +
            "c.content_hash as contentHash, c.token_count as tokenCount, c.metadata, c.vector_id as vectorId, " +
            "c.embedding_model as embeddingModel, c.chunk_size as chunkSize, c.chunk_overlap as chunkOverlap, " +
            "c.status, c.createtime, c.updatetime from file_rag_chunk c " +
            "join space_rag_chunk_ref r on r.file_chunk_id = c.id " +
            "join space_rag_document d on d.id = r.document_id " +
            "join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "where r.space_id = #{spaceId} and r.status = 1 and c.status = 1 and d.status = 1 " +
            "and d.index_status = 'SUCCESS' and d.vector_state = 'ACTIVE' and sf.status = 1 and sf.lifecycle_state='ACTIVE' and sf.searchable = 1 " +
            "and (c.metadata like concat('%', #{keyword}, '%') or d.file_name like concat('%', #{keyword}, '%') " +
            "or d.file_type like concat('%', #{keyword}, '%')) " +
            "order by c.updatetime desc limit #{limit}")
    List<FileRagChunk> searchBySpaceAndMetadata(@Param("spaceId") Long spaceId,
                                                @Param("keyword") String keyword,
                                                @Param("limit") Integer limit);

    /**
     * 查询 listRecentBySpace 相关逻辑。
     * @return 列表结果
     */
    @Select("select c.id, c.file_uuid as fileUuid, c.file_hash as fileHash, c.chunk_index as chunkIndex, c.content, " +
            "c.content_hash as contentHash, c.token_count as tokenCount, c.metadata, c.vector_id as vectorId, " +
            "c.embedding_model as embeddingModel, c.chunk_size as chunkSize, c.chunk_overlap as chunkOverlap, " +
            "c.status, c.createtime, c.updatetime from file_rag_chunk c " +
            "join space_rag_chunk_ref r on r.file_chunk_id = c.id " +
            "join space_rag_document d on d.id = r.document_id " +
            "join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "where r.space_id = #{spaceId} and r.status = 1 and c.status = 1 " +
            "and d.status = 1 and d.index_status = 'SUCCESS' and d.vector_state = 'ACTIVE' and sf.status = 1 and sf.lifecycle_state='ACTIVE' and sf.searchable = 1 " +
            "order by c.updatetime desc limit #{limit}")
    List<FileRagChunk> listRecentBySpace(@Param("spaceId") Long spaceId, @Param("limit") Integer limit);

    /**
     * 查询 listActiveBySpace 相关逻辑。
     * @return 列表结果
     */
    @Select("select c.id, c.file_uuid as fileUuid, c.file_hash as fileHash, c.chunk_index as chunkIndex, c.content, " +
            "c.content_hash as contentHash, c.token_count as tokenCount, c.metadata, c.vector_id as vectorId, " +
            "c.embedding_model as embeddingModel, c.chunk_size as chunkSize, c.chunk_overlap as chunkOverlap, " +
            "c.status, c.createtime, c.updatetime from file_rag_chunk c " +
            "join space_rag_chunk_ref r on r.file_chunk_id = c.id " +
            "join space_rag_document d on d.id = r.document_id " +
            "join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "where r.space_id = #{spaceId} and r.status = 1 and c.status = 1 " +
            "and d.status = 1 and d.index_status = 'SUCCESS' and d.vector_state = 'ACTIVE' and sf.status = 1 and sf.lifecycle_state='ACTIVE' and sf.searchable = 1 " +
            "order by c.updatetime desc")
    List<FileRagChunk> listActiveBySpace(@Param("spaceId") Long spaceId);

    @Select({"<script>select c.id, c.file_uuid as fileUuid, c.file_hash as fileHash, c.chunk_index as chunkIndex, c.content, " +
            "c.content_hash as contentHash, c.token_count as tokenCount, c.metadata, c.status, c.createtime, c.updatetime " +
            "from file_rag_chunk c join space_rag_chunk_ref r on r.file_chunk_id=c.id " +
            "join space_rag_document d on d.id=r.document_id " +
            "join space_file sf on sf.id=d.space_file_id and sf.space_id=d.space_id " +
            "where r.space_id=#{spaceId} and r.status=1 and c.status=1 and d.status=1 " +
            "and d.index_status='SUCCESS' and d.vector_state='ACTIVE' and sf.status=1 and sf.lifecycle_state='ACTIVE' and sf.searchable=1 and c.id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "order by c.id</script>"})
    List<FileRagChunk> listActiveBySpaceAndIds(@Param("spaceId") Long spaceId, @Param("ids") List<Long> ids);

    /**
     * 搜索 searchDocumentChunkHits 相关逻辑。
     * @return 列表结果
     */
    @Select("select r.document_id as documentId, c.id as chunkId, c.content as content " +
            "from file_rag_chunk c join space_rag_chunk_ref r on r.file_chunk_id = c.id " +
            "join space_rag_document d on d.id = r.document_id " +
            "join space_file sf on sf.id = d.space_file_id and sf.space_id = d.space_id " +
            "where r.space_id = #{spaceId} and r.status = 1 and c.status = 1 and d.status = 1 " +
            "and d.index_status = 'SUCCESS' and d.vector_state = 'ACTIVE' and sf.status = 1 and sf.lifecycle_state='ACTIVE' and sf.searchable = 1 " +
            "and c.content like concat('%', #{keyword}, '%') " +
            "order by c.updatetime desc limit #{limit}")
    List<SpaceDocumentChunkHitVO> searchDocumentChunkHits(@Param("spaceId") Long spaceId,
                                                          @Param("keyword") String keyword,
                                                          @Param("limit") Integer limit);

}
