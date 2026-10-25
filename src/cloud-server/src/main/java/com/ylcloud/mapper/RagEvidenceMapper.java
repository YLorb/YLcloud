package com.ylcloud.mapper;

import com.ylcloud.entity.RagEvidenceRecord;
import com.ylcloud.entity.RagTrustedAnswer;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RagEvidenceMapper {
    @Insert("insert into rag_evidence(id,user_id,space_id,space_file_id,revision_id,chunk_id,file_hash,content,citation_json,created_at) "
            + "values(#{id},#{userId},#{spaceId},#{spaceFileId},#{revisionId},#{chunkId},#{fileHash},#{content},#{citationJson},#{createdAt})")
    int insertEvidence(RagEvidenceRecord evidence);

    @Select("select id,user_id as userId,space_id as spaceId,space_file_id as spaceFileId,revision_id as revisionId,"
            + "chunk_id as chunkId,file_hash as fileHash,content,citation_json as citationJson,created_at as createdAt "
            + "from rag_evidence where id=#{id} and user_id=#{userId}")
    RagEvidenceRecord getEvidence(@Param("id") String id,@Param("userId") Long userId);

    @Insert("insert into rag_trusted_answer(id,user_id,content,answer_status,citations_json,space_ids_json,created_at) "
            + "values(#{id},#{userId},#{content},#{answerStatus},#{citationsJson},#{spaceIdsJson},#{createdAt})")
    int insertAnswer(RagTrustedAnswer answer);

    @Select("select id,user_id as userId,content,answer_status as answerStatus,citations_json as citationsJson,space_ids_json as spaceIdsJson,created_at as createdAt "
            + "from rag_trusted_answer where id=#{id} and user_id=#{userId}")
    RagTrustedAnswer getAnswer(@Param("id") String id,@Param("userId") Long userId);
}
