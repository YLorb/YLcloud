package com.ylcloud.mapper;

import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.VO.SpaceDocumentChunkHitVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 物理文件 RAG 文本分块 Mapper。
 */
@Mapper
public interface FileRagChunkMapper {

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into file_rag_chunk(file_uuid, file_hash, chunk_index, content, content_hash, token_count, metadata, vector_id, embedding_model, chunk_size, chunk_overlap, status, createtime, updatetime) " +
            "values(#{fileUuid}, #{fileHash}, #{chunkIndex}, #{content}, #{contentHash}, #{tokenCount}, #{metadata}, #{vectorId}, #{embeddingModel}, #{chunkSize}, #{chunkOverlap}, #{status}, #{createtime}, #{updatetime})")
    int insert(FileRagChunk chunk);

    @Select("select id, file_uuid as fileUuid, file_hash as fileHash, chunk_index as chunkIndex, content, content_hash as contentHash, " +
            "token_count as tokenCount, metadata, vector_id as vectorId, embedding_model as embeddingModel, chunk_size as chunkSize, " +
            "chunk_overlap as chunkOverlap, status, createtime, updatetime from file_rag_chunk " +
            "where file_uuid = #{fileUuid} and status = 1 order by chunk_index")
    List<FileRagChunk> listByFileUuid(@Param("fileUuid") String fileUuid);

    @Select("select id, file_uuid as fileUuid, file_hash as fileHash, chunk_index as chunkIndex, content, content_hash as contentHash, " +
            "token_count as tokenCount, metadata, vector_id as vectorId, embedding_model as embeddingModel, chunk_size as chunkSize, " +
            "chunk_overlap as chunkOverlap, status, createtime, updatetime from file_rag_chunk " +
            "where file_uuid = #{fileUuid} and file_hash = #{fileHash} and status = 1 order by chunk_index")
    List<FileRagChunk> listByFileUuidAndHash(@Param("fileUuid") String fileUuid, @Param("fileHash") String fileHash);

    @Select("select c.id, c.file_uuid as fileUuid, c.file_hash as fileHash, c.chunk_index as chunkIndex, c.content, " +
            "c.content_hash as contentHash, c.token_count as tokenCount, c.metadata, c.vector_id as vectorId, " +
            "c.embedding_model as embeddingModel, c.chunk_size as chunkSize, c.chunk_overlap as chunkOverlap, " +
            "c.status, c.createtime, c.updatetime from file_rag_chunk c " +
            "join space_rag_chunk_ref r on r.file_chunk_id = c.id " +
            "where r.space_id = #{spaceId} and r.status = 1 and c.status = 1 " +
            "and c.content like concat('%', #{keyword}, '%') order by c.updatetime desc limit #{limit}")
    List<FileRagChunk> searchBySpaceAndKeyword(@Param("spaceId") Long spaceId,
                                               @Param("keyword") String keyword,
                                               @Param("limit") Integer limit);

    @Select("select c.id, c.file_uuid as fileUuid, c.file_hash as fileHash, c.chunk_index as chunkIndex, c.content, " +
            "c.content_hash as contentHash, c.token_count as tokenCount, c.metadata, c.vector_id as vectorId, " +
            "c.embedding_model as embeddingModel, c.chunk_size as chunkSize, c.chunk_overlap as chunkOverlap, " +
            "c.status, c.createtime, c.updatetime from file_rag_chunk c " +
            "join space_rag_chunk_ref r on r.file_chunk_id = c.id " +
            "where r.space_id = #{spaceId} and r.status = 1 and c.status = 1 " +
            "order by c.updatetime desc limit #{limit}")
    List<FileRagChunk> listRecentBySpace(@Param("spaceId") Long spaceId, @Param("limit") Integer limit);

    @Select("select r.document_id as documentId, c.id as chunkId, c.content as content " +
            "from file_rag_chunk c join space_rag_chunk_ref r on r.file_chunk_id = c.id " +
            "join space_rag_document d on d.id = r.document_id " +
            "where r.space_id = #{spaceId} and r.status = 1 and c.status = 1 and d.status = 1 " +
            "and c.content like concat('%', #{keyword}, '%') " +
            "order by c.updatetime desc limit #{limit}")
    List<SpaceDocumentChunkHitVO> searchDocumentChunkHits(@Param("spaceId") Long spaceId,
                                                          @Param("keyword") String keyword,
                                                          @Param("limit") Integer limit);
}
