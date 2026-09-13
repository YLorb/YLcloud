package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceKnowledgeQuestion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SpaceKnowledgeQuestionMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_knowledge_question(space_id, document_id, question, source, confidence, status, createtime, updatetime) " +
            "values(#{spaceId}, #{documentId}, #{question}, #{source}, #{confidence}, #{status}, #{createtime}, #{updatetime})")
    int insert(SpaceKnowledgeQuestion question);

    @Delete("delete from space_knowledge_question where space_id = #{spaceId} and document_id = #{documentId}")
    int deleteByDocumentId(@Param("spaceId") Long spaceId, @Param("documentId") Long documentId);

    @Select("select id, space_id as spaceId, document_id as documentId, question, source, confidence, status, createtime, updatetime " +
            "from space_knowledge_question where space_id = #{spaceId} and document_id = #{documentId} and status = 1 order by id")
    List<SpaceKnowledgeQuestion> listByDocumentId(@Param("spaceId") Long spaceId, @Param("documentId") Long documentId);
}
