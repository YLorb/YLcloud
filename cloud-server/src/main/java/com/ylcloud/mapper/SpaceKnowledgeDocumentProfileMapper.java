package com.ylcloud.mapper;

import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SpaceKnowledgeDocumentProfileMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("insert into space_knowledge_document_profile(space_id, document_id, space_file_id, title, summary, keywords_json, tags_json, category, language, document_type, quality_score, profile_status, raw_llm_output, normalized_profile_json, quality_detail_json, quality_issue_json, score_before_repair, score_after_repair, review_status, review_reason, source_chunk_ids, source_chunk_count, source_character_count, source_snapshot_signature, source_snapshot_revision, schema_valid, repair_attempt, repair_reason, profile_version, source_file_hash, source_parser_version, profile_schema_version, error_message, status, createtime, updatetime) " +
            "values(#{spaceId}, #{documentId}, #{spaceFileId}, #{title}, #{summary}, #{keywordsJson}, #{tagsJson}, #{category}, #{language}, #{documentType}, #{qualityScore}, #{profileStatus}, #{rawLlmOutput}, #{normalizedProfileJson}, #{qualityDetailJson}, #{qualityIssueJson}, #{scoreBeforeRepair}, #{scoreAfterRepair}, #{reviewStatus}, #{reviewReason}, #{sourceChunkIds}, #{sourceChunkCount}, #{sourceCharacterCount}, #{sourceSnapshotSignature}, coalesce(#{sourceSnapshotRevision}, 0), #{schemaValid}, #{repairAttempt}, #{repairReason}, #{profileVersion}, #{sourceFileHash}, #{sourceParserVersion}, #{profileSchemaVersion}, #{errorMessage}, #{status}, #{createtime}, #{updatetime}) " +
            "on duplicate key update id = last_insert_id(id), space_file_id = values(space_file_id), title = values(title), summary = values(summary), " +
            "keywords_json = values(keywords_json), tags_json = values(tags_json), category = values(category), language = values(language), " +
            "document_type = values(document_type), quality_score = values(quality_score), profile_status = values(profile_status), " +
            "raw_llm_output = values(raw_llm_output), normalized_profile_json = values(normalized_profile_json), quality_detail_json = values(quality_detail_json), " +
            "quality_issue_json = values(quality_issue_json), score_before_repair = values(score_before_repair), score_after_repair = values(score_after_repair), " +
            "review_status = values(review_status), review_reason = values(review_reason), " +
            "source_chunk_ids = if(values(source_snapshot_signature) is null, source_chunk_ids, values(source_chunk_ids)), " +
            "source_chunk_count = if(values(source_snapshot_signature) is null, source_chunk_count, values(source_chunk_count)), " +
            "source_character_count = if(values(source_snapshot_signature) is null, source_character_count, values(source_character_count)), " +
            "source_snapshot_signature = coalesce(values(source_snapshot_signature), source_snapshot_signature), " +
            "source_snapshot_revision = source_snapshot_revision + if(values(source_snapshot_signature) is null, 0, 1), schema_valid = values(schema_valid), " +
            "repair_attempt = values(repair_attempt), repair_reason = values(repair_reason), profile_version = profile_version + 1, " +
            "source_file_hash = values(source_file_hash), source_parser_version = values(source_parser_version), profile_schema_version = values(profile_schema_version), " +
            "error_message = values(error_message), status = values(status), updatetime = values(updatetime)")
    int upsert(SpaceKnowledgeDocumentProfile profile);

    @Select("select id, space_id as spaceId, document_id as documentId, space_file_id as spaceFileId, title, summary, " +
            "keywords_json as keywordsJson, tags_json as tagsJson, category, language, document_type as documentType, " +
            "quality_score as qualityScore, profile_status as profileStatus, raw_llm_output as rawLlmOutput, normalized_profile_json as normalizedProfileJson, " +
            "quality_detail_json as qualityDetailJson, quality_issue_json as qualityIssueJson, score_before_repair as scoreBeforeRepair, score_after_repair as scoreAfterRepair, " +
            "review_status as reviewStatus, review_reason as reviewReason, source_chunk_ids as sourceChunkIds, source_chunk_count as sourceChunkCount, " +
            "source_character_count as sourceCharacterCount, source_snapshot_signature as sourceSnapshotSignature, source_snapshot_revision as sourceSnapshotRevision, " +
            "schema_valid as schemaValid, repair_attempt as repairAttempt, repair_reason as repairReason, " +
            "profile_version as profileVersion, current_version_id as currentVersionId, latest_version_id as latestVersionId, latest_asset_state as latestAssetState, latest_confidence as latestConfidence, latest_conflict_reason as latestConflictReason, source_file_hash as sourceFileHash, " +
            "source_parser_version as sourceParserVersion, profile_schema_version as profileSchemaVersion, error_message as errorMessage, status, createtime, updatetime " +
            "from space_knowledge_document_profile where space_id = #{spaceId} and document_id = #{documentId} and status = 1")
    SpaceKnowledgeDocumentProfile getByDocumentId(@Param("spaceId") Long spaceId, @Param("documentId") Long documentId);

    @Select("select id, space_id as spaceId, document_id as documentId, space_file_id as spaceFileId, title, summary, " +
            "keywords_json as keywordsJson, tags_json as tagsJson, category, language, document_type as documentType, quality_score as qualityScore, " +
            "profile_status as profileStatus, review_status as reviewStatus, review_reason as reviewReason, current_version_id as currentVersionId, " +
            "latest_version_id as latestVersionId, latest_asset_state as latestAssetState, latest_confidence as latestConfidence, latest_conflict_reason as latestConflictReason, " +
            "source_file_hash as sourceFileHash, source_parser_version as sourceParserVersion, profile_schema_version as profileSchemaVersion, " +
            "error_message as errorMessage, status, createtime, updatetime from space_knowledge_document_profile " +
            "where space_id=#{spaceId} and document_id=#{documentId} and status=1 for update")
    SpaceKnowledgeDocumentProfile getByDocumentIdForUpdate(@Param("spaceId") Long spaceId,@Param("documentId") Long documentId);

    @Select("select id, source_chunk_ids as sourceChunkIds, source_chunk_count as sourceChunkCount, " +
            "source_character_count as sourceCharacterCount, source_parser_version as sourceParserVersion, " +
            "source_snapshot_signature as sourceSnapshotSignature, source_snapshot_revision as sourceSnapshotRevision " +
            "from space_knowledge_document_profile where space_id = #{spaceId} and document_id = #{documentId} and status = 1 for update")
    SpaceKnowledgeDocumentProfile getSourceSnapshotForUpdate(@Param("spaceId") Long spaceId,
                                                              @Param("documentId") Long documentId);

    @Select("select id, space_id as spaceId, document_id as documentId, space_file_id as spaceFileId, title, summary, " +
            "keywords_json as keywordsJson, tags_json as tagsJson, category, language, document_type as documentType, " +
            "quality_score as qualityScore, profile_status as profileStatus, raw_llm_output as rawLlmOutput, normalized_profile_json as normalizedProfileJson, " +
            "quality_detail_json as qualityDetailJson, quality_issue_json as qualityIssueJson, score_before_repair as scoreBeforeRepair, score_after_repair as scoreAfterRepair, " +
            "review_status as reviewStatus, review_reason as reviewReason, source_chunk_ids as sourceChunkIds, source_chunk_count as sourceChunkCount, " +
            "source_character_count as sourceCharacterCount, source_snapshot_signature as sourceSnapshotSignature, source_snapshot_revision as sourceSnapshotRevision, " +
            "schema_valid as schemaValid, repair_attempt as repairAttempt, repair_reason as repairReason, " +
            "profile_version as profileVersion, current_version_id as currentVersionId, latest_version_id as latestVersionId, latest_asset_state as latestAssetState, latest_confidence as latestConfidence, latest_conflict_reason as latestConflictReason, source_file_hash as sourceFileHash, " +
            "source_parser_version as sourceParserVersion, profile_schema_version as profileSchemaVersion, error_message as errorMessage, status, createtime, updatetime " +
            "from space_knowledge_document_profile where space_id = #{spaceId} and status = 1 order by updatetime desc")
    List<SpaceKnowledgeDocumentProfile> listBySpaceId(@Param("spaceId") Long spaceId);

    @Select("select id, space_id as spaceId, document_id as documentId, space_file_id as spaceFileId, title, summary, " +
            "keywords_json as keywordsJson, tags_json as tagsJson, category, language, document_type as documentType, " +
            "quality_score as qualityScore, profile_status as profileStatus, raw_llm_output as rawLlmOutput, normalized_profile_json as normalizedProfileJson, " +
            "quality_detail_json as qualityDetailJson, quality_issue_json as qualityIssueJson, score_before_repair as scoreBeforeRepair, score_after_repair as scoreAfterRepair, " +
            "review_status as reviewStatus, review_reason as reviewReason, source_chunk_ids as sourceChunkIds, source_chunk_count as sourceChunkCount, " +
            "source_character_count as sourceCharacterCount, source_snapshot_signature as sourceSnapshotSignature, source_snapshot_revision as sourceSnapshotRevision, " +
            "schema_valid as schemaValid, repair_attempt as repairAttempt, repair_reason as repairReason, " +
            "profile_version as profileVersion, current_version_id as currentVersionId, latest_version_id as latestVersionId, latest_asset_state as latestAssetState, latest_confidence as latestConfidence, latest_conflict_reason as latestConflictReason, source_file_hash as sourceFileHash, " +
            "source_parser_version as sourceParserVersion, profile_schema_version as profileSchemaVersion, error_message as errorMessage, status, createtime, updatetime " +
            "from space_knowledge_document_profile where space_id = #{spaceId} and status = 1 " +
            "and (#{category} is null or #{category} = '' or category = #{category}) " +
            "and (#{profileStatus} is null or #{profileStatus} = '' or profile_status = #{profileStatus} or latest_asset_state = #{profileStatus} " +
            "or (#{profileStatus} = 'INVALID' and profile_status in ('INVALID','FAILED'))) " +
            "and (#{tag} is null or #{tag} = '' or tags_json like concat('%', #{tag}, '%')) " +
            "order by updatetime desc")
    List<SpaceKnowledgeDocumentProfile> listFiltered(@Param("spaceId") Long spaceId,
                                                     @Param("category") String category,
                                                     @Param("tag") String tag,
                                                     @Param("profileStatus") String profileStatus);

    @Update("update space_knowledge_document_profile set title = #{title}, summary = #{summary}, keywords_json = #{keywordsJson}, " +
            "tags_json = #{tagsJson}, category = #{category}, profile_status = #{profileStatus}, review_status = 'APPROVED', error_message = #{errorMessage}, " +
            "updatetime = #{updateTime} where space_id = #{spaceId} and document_id = #{documentId} and status = 1")
    int updateManual(@Param("spaceId") Long spaceId,
                     @Param("documentId") Long documentId,
                     @Param("title") String title,
                     @Param("summary") String summary,
                     @Param("keywordsJson") String keywordsJson,
                     @Param("tagsJson") String tagsJson,
                     @Param("category") String category,
                     @Param("profileStatus") String profileStatus,
                     @Param("errorMessage") String errorMessage,
                     @Param("updateTime") java.time.LocalDateTime updateTime);

    @Update("update space_knowledge_document_profile set current_version_id=#{versionId},latest_version_id=#{versionId}," +
            "latest_asset_state='ACTIVE',latest_confidence=#{confidence},latest_conflict_reason=null,updatetime=#{updateTime} where id=#{id}")
    int updateActiveVersionRefs(@Param("id") Long id,@Param("versionId") Long versionId,
                                @Param("confidence") java.math.BigDecimal confidence,
                                @Param("updateTime") java.time.LocalDateTime updateTime);

    @Update("update space_knowledge_document_profile set latest_version_id=#{versionId},latest_asset_state=#{assetState}," +
            "latest_confidence=#{confidence},latest_conflict_reason=#{conflictReason},updatetime=#{updateTime} where id=#{id}")
    int updateLatestVersionRef(@Param("id") Long id,@Param("versionId") Long versionId,
                               @Param("assetState") String assetState,@Param("confidence") java.math.BigDecimal confidence,
                               @Param("conflictReason") String conflictReason,
                               @Param("updateTime") java.time.LocalDateTime updateTime);

    /**
     * 同步检索源切片快照，不重建或覆盖知识画像内容。
     */
    @Update("update space_knowledge_document_profile set source_chunk_ids = #{sourceChunkIds}, " +
            "source_chunk_count = #{sourceChunkCount}, source_character_count = #{sourceCharacterCount}, " +
            "source_parser_version = #{sourceParserVersion}, source_snapshot_signature = #{sourceSnapshotSignature}, " +
            "source_snapshot_revision = source_snapshot_revision + 1, updatetime = #{updateTime} " +
            "where space_id = #{spaceId} and document_id = #{documentId} and status = 1 " +
            "and source_snapshot_revision = #{expectedRevision}")
    int syncRetrievalSource(@Param("spaceId") Long spaceId,
                            @Param("documentId") Long documentId,
                            @Param("sourceChunkIds") String sourceChunkIds,
                            @Param("sourceChunkCount") Integer sourceChunkCount,
                            @Param("sourceCharacterCount") Integer sourceCharacterCount,
                            @Param("sourceParserVersion") String sourceParserVersion,
                            @Param("sourceSnapshotSignature") String sourceSnapshotSignature,
                            @Param("expectedRevision") Long expectedRevision,
                            @Param("updateTime") java.time.LocalDateTime updateTime);

    @Update("update space_knowledge_document_profile set title = #{title}, summary = #{summary}, keywords_json = #{keywordsJson}, tags_json = #{tagsJson}, " +
            "category = #{category}, language = #{language}, document_type = #{documentType}, quality_score = #{qualityScore}, profile_status = #{profileStatus}, " +
            "quality_detail_json = #{qualityDetailJson}, quality_issue_json = #{qualityIssueJson}, score_before_repair = #{scoreBeforeRepair}, score_after_repair = #{scoreAfterRepair}, " +
            "review_status = #{reviewStatus}, review_reason = #{reviewReason}, schema_valid = #{schemaValid}, repair_attempt = #{repairAttempt}, repair_reason = #{repairReason}, " +
            "source_file_hash = #{sourceFileHash}, source_parser_version = #{sourceParserVersion}, profile_schema_version = #{profileSchemaVersion}, " +
            "current_version_id = #{currentVersionId}, latest_version_id = #{latestVersionId}, latest_asset_state=#{latestAssetState}, " +
            "latest_confidence=#{latestConfidence}, latest_conflict_reason=#{latestConflictReason}, error_message = #{errorMessage}, updatetime = #{updatetime} " +
            "where id = #{id}")
    int restoreFromVersion(SpaceKnowledgeDocumentProfile profile);
}
