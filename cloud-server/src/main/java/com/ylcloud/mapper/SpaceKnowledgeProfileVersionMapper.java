package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceKnowledgeProfileVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SpaceKnowledgeProfileVersionMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_knowledge_profile_version(profile_id, space_id, document_id, version_no, document_version_id, source_type, model_name, prompt_version, schema_version, quality_score, profile_snapshot, change_summary, created_by, created_time) " +
            "values(#{profileId}, #{spaceId}, #{documentId}, #{versionNo}, #{documentVersionId}, #{sourceType}, #{modelName}, #{promptVersion}, #{schemaVersion}, #{qualityScore}, #{profileSnapshot}, #{changeSummary}, #{createdBy}, #{createdTime})")
    int insert(SpaceKnowledgeProfileVersion version);

    @Select("select coalesce(max(version_no),0) from space_knowledge_profile_version where profile_id = #{profileId}")
    Integer maxVersionNo(@Param("profileId") Long profileId);

    @Select("select id, profile_id as profileId, space_id as spaceId, document_id as documentId, version_no as versionNo, document_version_id as documentVersionId, " +
            "source_type as sourceType, model_name as modelName, prompt_version as promptVersion, schema_version as schemaVersion, quality_score as qualityScore, " +
            "profile_snapshot as profileSnapshot, change_summary as changeSummary, created_by as createdBy, created_time as createdTime " +
            "from space_knowledge_profile_version where profile_id = #{profileId} order by version_no desc")
    List<SpaceKnowledgeProfileVersion> listByProfileId(@Param("profileId") Long profileId);

    @Select("select id, profile_id as profileId, space_id as spaceId, document_id as documentId, version_no as versionNo, document_version_id as documentVersionId, " +
            "source_type as sourceType, model_name as modelName, prompt_version as promptVersion, schema_version as schemaVersion, quality_score as qualityScore, " +
            "profile_snapshot as profileSnapshot, change_summary as changeSummary, created_by as createdBy, created_time as createdTime " +
            "from space_knowledge_profile_version where id = #{id}")
    SpaceKnowledgeProfileVersion getById(@Param("id") Long id);
}
