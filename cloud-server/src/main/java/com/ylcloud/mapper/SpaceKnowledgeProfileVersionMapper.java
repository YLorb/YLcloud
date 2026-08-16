package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceKnowledgeProfileVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SpaceKnowledgeProfileVersionMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_knowledge_profile_version(profile_id, space_id, document_id, version_no, document_version_id, source_type, model_name, prompt_version, schema_version, quality_score, asset_state, confidence, conflict_reason, source_file_hash, source_parser_version, profile_snapshot, change_summary, created_by, reviewed_by, activated_at, created_time) " +
            "values(#{profileId}, #{spaceId}, #{documentId}, #{versionNo}, #{documentVersionId}, #{sourceType}, #{modelName}, #{promptVersion}, #{schemaVersion}, #{qualityScore}, #{assetState}, #{confidence}, #{conflictReason}, #{sourceFileHash}, #{sourceParserVersion}, #{profileSnapshot}, #{changeSummary}, #{createdBy}, #{reviewedBy}, #{activatedAt}, #{createdTime})")
    int insert(SpaceKnowledgeProfileVersion version);

    @Select("select coalesce(max(version_no),0) from space_knowledge_profile_version where profile_id = #{profileId}")
    Integer maxVersionNo(@Param("profileId") Long profileId);

    @Select("select id, profile_id as profileId, space_id as spaceId, document_id as documentId, version_no as versionNo, document_version_id as documentVersionId, " +
            "source_type as sourceType, model_name as modelName, prompt_version as promptVersion, schema_version as schemaVersion, quality_score as qualityScore, " +
            "asset_state as assetState, confidence, conflict_reason as conflictReason, source_file_hash as sourceFileHash, source_parser_version as sourceParserVersion, " +
            "profile_snapshot as profileSnapshot, change_summary as changeSummary, created_by as createdBy, reviewed_by as reviewedBy, activated_at as activatedAt, superseded_at as supersededAt, created_time as createdTime " +
            "from space_knowledge_profile_version where profile_id = #{profileId} order by version_no desc")
    List<SpaceKnowledgeProfileVersion> listByProfileId(@Param("profileId") Long profileId);

    @Select("select id, profile_id as profileId, space_id as spaceId, document_id as documentId, version_no as versionNo, document_version_id as documentVersionId, " +
            "source_type as sourceType, model_name as modelName, prompt_version as promptVersion, schema_version as schemaVersion, quality_score as qualityScore, " +
            "asset_state as assetState, confidence, conflict_reason as conflictReason, source_file_hash as sourceFileHash, source_parser_version as sourceParserVersion, " +
            "profile_snapshot as profileSnapshot, change_summary as changeSummary, created_by as createdBy, reviewed_by as reviewedBy, activated_at as activatedAt, superseded_at as supersededAt, created_time as createdTime " +
            "from space_knowledge_profile_version where id = #{id}")
    SpaceKnowledgeProfileVersion getById(@Param("id") Long id);

    @Update("update space_knowledge_profile_version set asset_state='SUPERSEDED',superseded_at=#{now} " +
            "where profile_id=#{profileId} and asset_state='ACTIVE'")
    int supersedeActive(@Param("profileId") Long profileId,@Param("now") java.time.LocalDateTime now);

    @Update("update space_knowledge_profile_version set asset_state='ACTIVE',reviewed_by=#{reviewedBy}," +
            "activated_at=#{now},superseded_at=null where id=#{id} and asset_state='NEEDS_REVIEW'")
    int activateReviewed(@Param("id") Long id,@Param("reviewedBy") Long reviewedBy,
                         @Param("now") java.time.LocalDateTime now);
}
