package com.ylcloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.VO.SpaceKnowledgeProfileDiffVO;
import com.ylcloud.VO.SpaceKnowledgeProfileVersionVO;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.SpaceKnowledgeAuditLog;
import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;
import com.ylcloud.entity.SpaceKnowledgeProfileVersion;
import com.ylcloud.entity.SpaceKnowledgeQuestion;
import com.ylcloud.mapper.SpaceKnowledgeAuditLogMapper;
import com.ylcloud.mapper.SpaceKnowledgeDocumentProfileMapper;
import com.ylcloud.mapper.SpaceKnowledgeProfileVersionMapper;
import com.ylcloud.mapper.SpaceKnowledgeQuestionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeProfileAssetService {
    private static final int MAX_QUESTIONS = 8;

    private final SpaceKnowledgeDocumentProfileMapper profileMapper;
    private final SpaceKnowledgeProfileVersionMapper versionMapper;
    private final SpaceKnowledgeQuestionMapper questionMapper;
    private final SpaceKnowledgeAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    public KnowledgeProfileAssetService(SpaceKnowledgeDocumentProfileMapper profileMapper,
                                        SpaceKnowledgeProfileVersionMapper versionMapper,
                                        SpaceKnowledgeQuestionMapper questionMapper,
                                        SpaceKnowledgeAuditLogMapper auditLogMapper,
                                        ObjectMapper objectMapper) {
        this.profileMapper = profileMapper;
        this.versionMapper = versionMapper;
        this.questionMapper = questionMapper;
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
    }

    public SpaceKnowledgeProfileVersion createVersion(SpaceKnowledgeDocumentProfile profile,
                                                      List<String> questions,
                                                      String sourceType,
                                                      Long createdBy,
                                                      String changeSummary) {
        SpaceKnowledgeProfileVersion version = new SpaceKnowledgeProfileVersion();
        version.setProfileId(profile.getId());
        version.setSpaceId(profile.getSpaceId());
        version.setDocumentId(profile.getDocumentId());
        version.setVersionNo(versionMapper.maxVersionNo(profile.getId()) + 1);
        version.setSourceType(sourceType);
        version.setSchemaVersion("profile-v1");
        version.setQualityScore(profile.getQualityScore() == null ? BigDecimal.ZERO : profile.getQualityScore());
        version.setProfileSnapshot(snapshot(profile,questions));
        version.setChangeSummary(changeSummary);
        version.setCreatedBy(createdBy);
        version.setCreatedTime(LocalDateTime.now());
        versionMapper.insert(version);
        profileMapper.updateVersionRefs(profile.getId(),version.getId(),version.getId(),LocalDateTime.now());
        return version;
    }

    public List<SpaceKnowledgeProfileVersionVO> listVersions(Long spaceId, Long documentId) {
        SpaceKnowledgeDocumentProfile profile = requireProfile(spaceId,documentId);
        return versionMapper.listByProfileId(profile.getId()).stream().map(this::toVersionVO).toList();
    }

    public SpaceKnowledgeProfileDiffVO diff(Long spaceId, Long documentId, Long versionId, Long compareToVersionId) {
        SpaceKnowledgeDocumentProfile profile = requireProfile(spaceId,documentId);
        SpaceKnowledgeProfileVersion after = requireVersion(profile,versionId);
        Long beforeId = compareToVersionId == null ? profile.getCurrentVersionId() : compareToVersionId;
        SpaceKnowledgeProfileVersion before = beforeId == null ? null : requireVersion(profile,beforeId);
        Map<String, Object> beforeMap = before == null ? Map.of() : snapshotMap(before.getProfileSnapshot());
        Map<String, Object> afterMap = snapshotMap(after.getProfileSnapshot());
        SpaceKnowledgeProfileDiffVO vo = new SpaceKnowledgeProfileDiffVO();
        vo.setBeforeVersionId(before == null ? null : before.getId());
        vo.setAfterVersionId(after.getId());
        vo.setSummaryChanged(!stringValue(beforeMap,"summary").equals(stringValue(afterMap,"summary")));
        vo.setCategoryBefore(stringValue(beforeMap,"category"));
        vo.setCategoryAfter(stringValue(afterMap,"category"));
        vo.setTagsAdded(added(stringList(beforeMap,"tags"),stringList(afterMap,"tags")));
        vo.setTagsRemoved(removed(stringList(beforeMap,"tags"),stringList(afterMap,"tags")));
        vo.setKeywordsAdded(added(stringList(beforeMap,"keywords"),stringList(afterMap,"keywords")));
        vo.setKeywordsRemoved(removed(stringList(beforeMap,"keywords"),stringList(afterMap,"keywords")));
        vo.setQuestionsAdded(added(stringList(beforeMap,"questions"),stringList(afterMap,"questions")));
        vo.setQuestionsRemoved(removed(stringList(beforeMap,"questions"),stringList(afterMap,"questions")));
        vo.setQualityScoreBefore(stringValue(beforeMap,"qualityScore"));
        vo.setQualityScoreAfter(stringValue(afterMap,"qualityScore"));
        return vo;
    }

    @Transactional
    public SpaceKnowledgeDocumentProfile restoreVersion(Long spaceId, Long documentId, Long versionId, Long operatorId) {
        SpaceKnowledgeDocumentProfile current = requireProfile(spaceId,documentId);
        SpaceKnowledgeProfileVersion source = requireVersion(current,versionId);
        String before = snapshot(current,questions(spaceId,documentId));
        Map<String, Object> snapshot = snapshotMap(source.getProfileSnapshot());
        SpaceKnowledgeDocumentProfile restored = applySnapshot(current,snapshot);
        restored.setUpdatetime(LocalDateTime.now());
        restored.setCurrentVersionId(null);
        restored.setLatestVersionId(null);
        profileMapper.restoreFromVersion(restored);
        questionMapper.deleteByDocumentId(spaceId,documentId);
        saveQuestions(spaceId,documentId,stringList(snapshot,"questions"));
        SpaceKnowledgeDocumentProfile loaded = profileMapper.getByDocumentId(spaceId,documentId);
        SpaceKnowledgeProfileVersion restoredVersion = createVersion(loaded,stringList(snapshot,"questions"),"RESTORED",operatorId,
                "Restored from version " + source.getVersionNo());
        loaded.setCurrentVersionId(restoredVersion.getId());
        loaded.setLatestVersionId(restoredVersion.getId());
        audit(spaceId,operatorId,"PROFILE_RESTORE","KNOWLEDGE_PROFILE",loaded.getId(),before,snapshot(loaded,stringList(snapshot,"questions")));
        return profileMapper.getByDocumentId(spaceId,documentId);
    }

    public void audit(Long spaceId, Long operatorId, String action, String resourceType, Long resourceId, String beforeSnapshot, String afterSnapshot) {
        SpaceKnowledgeAuditLog auditLog = new SpaceKnowledgeAuditLog();
        auditLog.setSpaceId(spaceId);
        auditLog.setOperatorId(operatorId);
        auditLog.setAction(action);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setBeforeSnapshot(beforeSnapshot);
        auditLog.setAfterSnapshot(afterSnapshot);
        auditLog.setCreatedTime(LocalDateTime.now());
        auditLogMapper.insert(auditLog);
    }

    public String snapshot(SpaceKnowledgeDocumentProfile profile, List<String> questions) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title",profile.getTitle());
        data.put("summary",profile.getSummary());
        data.put("category",profile.getCategory());
        data.put("tags",jsonArray(profile.getTagsJson()));
        data.put("keywords",jsonArray(profile.getKeywordsJson()));
        data.put("questions",questions == null ? List.of() : questions);
        data.put("language",profile.getLanguage());
        data.put("documentType",profile.getDocumentType());
        data.put("qualityScore",profile.getQualityScore());
        data.put("profileStatus",profile.getProfileStatus());
        data.put("reviewStatus",profile.getReviewStatus());
        data.put("reviewReason",profile.getReviewReason());
        data.put("qualityDetailJson",profile.getQualityDetailJson());
        data.put("qualityIssueJson",profile.getQualityIssueJson());
        data.put("scoreBeforeRepair",profile.getScoreBeforeRepair());
        data.put("scoreAfterRepair",profile.getScoreAfterRepair());
        data.put("schemaValid",profile.getSchemaValid());
        data.put("repairAttempt",profile.getRepairAttempt());
        data.put("repairReason",profile.getRepairReason());
        data.put("sourceFileHash",profile.getSourceFileHash());
        data.put("sourceParserVersion",profile.getSourceParserVersion());
        data.put("profileSchemaVersion",profile.getProfileSchemaVersion());
        return toJson(data);
    }

    private SpaceKnowledgeDocumentProfile applySnapshot(SpaceKnowledgeDocumentProfile current, Map<String, Object> snapshot) {
        current.setTitle(stringValue(snapshot,"title"));
        current.setSummary(stringValue(snapshot,"summary"));
        current.setCategory(stringValue(snapshot,"category"));
        current.setTagsJson(toJson(stringList(snapshot,"tags")));
        current.setKeywordsJson(toJson(stringList(snapshot,"keywords")));
        current.setLanguage(stringValue(snapshot,"language"));
        current.setDocumentType(stringValue(snapshot,"documentType"));
        current.setQualityScore(decimalValue(snapshot,"qualityScore"));
        current.setProfileStatus(stringValue(snapshot,"profileStatus"));
        current.setReviewStatus(stringValue(snapshot,"reviewStatus"));
        current.setReviewReason(stringValue(snapshot,"reviewReason"));
        current.setQualityDetailJson(stringValue(snapshot,"qualityDetailJson"));
        current.setQualityIssueJson(stringValue(snapshot,"qualityIssueJson"));
        current.setScoreBeforeRepair(decimalValue(snapshot,"scoreBeforeRepair"));
        current.setScoreAfterRepair(decimalValue(snapshot,"scoreAfterRepair"));
        current.setSchemaValid(booleanValue(snapshot,"schemaValid"));
        current.setRepairAttempt(intValue(snapshot,"repairAttempt"));
        current.setRepairReason(stringValue(snapshot,"repairReason"));
        current.setSourceFileHash(stringValue(snapshot,"sourceFileHash"));
        current.setSourceParserVersion(stringValue(snapshot,"sourceParserVersion"));
        current.setProfileSchemaVersion(stringValue(snapshot,"profileSchemaVersion"));
        current.setErrorMessage(null);
        return current;
    }

    private SpaceKnowledgeDocumentProfile requireProfile(Long spaceId, Long documentId) {
        SpaceKnowledgeDocumentProfile profile = profileMapper.getByDocumentId(spaceId,documentId);
        if(profile == null) {
            throw new BaseException("knowledge profile not found");
        }
        return profile;
    }

    private SpaceKnowledgeProfileVersion requireVersion(SpaceKnowledgeDocumentProfile profile, Long versionId) {
        SpaceKnowledgeProfileVersion version = versionMapper.getById(versionId);
        if(version == null || !profile.getId().equals(version.getProfileId())) {
            throw new BaseException("knowledge profile version not found");
        }
        return version;
    }

    private SpaceKnowledgeProfileVersionVO toVersionVO(SpaceKnowledgeProfileVersion version) {
        SpaceKnowledgeProfileVersionVO vo = new SpaceKnowledgeProfileVersionVO();
        vo.setId(version.getId());
        vo.setProfileId(version.getProfileId());
        vo.setSpaceId(version.getSpaceId());
        vo.setDocumentId(version.getDocumentId());
        vo.setVersionNo(version.getVersionNo());
        vo.setDocumentVersionId(version.getDocumentVersionId());
        vo.setSourceType(version.getSourceType());
        vo.setModelName(version.getModelName());
        vo.setPromptVersion(version.getPromptVersion());
        vo.setSchemaVersion(version.getSchemaVersion());
        vo.setQualityScore(version.getQualityScore());
        vo.setProfileSnapshot(version.getProfileSnapshot());
        vo.setChangeSummary(version.getChangeSummary());
        vo.setCreatedBy(version.getCreatedBy());
        vo.setCreatedTime(version.getCreatedTime());
        return vo;
    }

    private List<String> questions(Long spaceId, Long documentId) {
        List<String> result = new ArrayList<>();
        for(SpaceKnowledgeQuestion question : questionMapper.listByDocumentId(spaceId,documentId)) {
            result.add(question.getQuestion());
        }
        return result;
    }

    private void saveQuestions(Long spaceId, Long documentId, List<String> questions) {
        Set<String> deduped = new LinkedHashSet<>();
        for(String question : questions == null ? List.<String>of() : questions) {
            if(question != null && !question.isBlank()) {
                deduped.add(question.trim());
            }
            if(deduped.size() >= MAX_QUESTIONS) {
                break;
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for(String value : deduped) {
            SpaceKnowledgeQuestion question = new SpaceKnowledgeQuestion();
            question.setSpaceId(spaceId);
            question.setDocumentId(documentId);
            question.setQuestion(value);
            question.setSource("GENERATED");
            question.setConfidence(BigDecimal.valueOf(0.80));
            question.setStatus(StatusConstant.ENABLE);
            question.setCreatetime(now);
            question.setUpdatetime(now);
            questionMapper.insert(question);
        }
    }

    private Map<String, Object> snapshotMap(String json) {
        try {
            return objectMapper.readValue(json,new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<String> jsonArray(String json) {
        if(json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> values = new ArrayList<>();
            if(node.isArray()) {
                for(JsonNode item : node) {
                    values.add(item.asText());
                }
            }
            return values;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> stringList(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if(value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private List<String> added(List<String> before, List<String> after) {
        return after.stream().filter(item -> !before.contains(item)).toList();
    }

    private List<String> removed(List<String> before, List<String> after) {
        return before.stream().filter(item -> !after.contains(item)).toList();
    }

    private String stringValue(Map<String, Object> map, String field) {
        Object value = map.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private BigDecimal decimalValue(Map<String, Object> map, String field) {
        String value = stringValue(map,field);
        if(value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    private Boolean booleanValue(Map<String, Object> map, String field) {
        Object value = map.get(field);
        return value instanceof Boolean bool ? bool : Boolean.valueOf(String.valueOf(value));
    }

    private Integer intValue(Map<String, Object> map, String field) {
        String value = stringValue(map,field);
        if(value.isBlank()) {
            return 0;
        }
        return Integer.valueOf(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
