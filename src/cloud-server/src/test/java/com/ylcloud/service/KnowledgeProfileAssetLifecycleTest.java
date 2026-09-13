package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;
import com.ylcloud.entity.SpaceKnowledgeProfileVersion;
import com.ylcloud.mapper.SpaceKnowledgeAuditLogMapper;
import com.ylcloud.mapper.SpaceKnowledgeDocumentProfileMapper;
import com.ylcloud.mapper.SpaceKnowledgeProfileVersionMapper;
import com.ylcloud.mapper.SpaceKnowledgeQuestionMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KnowledgeProfileAssetLifecycleTest {
    private final SpaceKnowledgeDocumentProfileMapper profileMapper = mock(SpaceKnowledgeDocumentProfileMapper.class);
    private final SpaceKnowledgeProfileVersionMapper versionMapper = mock(SpaceKnowledgeProfileVersionMapper.class);
    private final SpaceKnowledgeQuestionMapper questionMapper = mock(SpaceKnowledgeQuestionMapper.class);
    private final SpaceKnowledgeAuditLogMapper auditMapper = mock(SpaceKnowledgeAuditLogMapper.class);
    private final KnowledgeProfileAssetService service = new KnowledgeProfileAssetService(
            profileMapper,versionMapper,questionMapper,auditMapper,new ObjectMapper());

    @Test
    void lowConfidenceRevisionBecomesReviewCandidateWithoutSupersedingActiveVersion() {
        SpaceKnowledgeDocumentProfile identity = profile("VALID",BigDecimal.valueOf(90));
        identity.setId(5L);
        identity.setCurrentVersionId(8L);
        SpaceKnowledgeDocumentProfile candidate = profile("NEEDS_REVIEW",BigDecimal.valueOf(64));
        candidate.setReviewReason("LOW_CONFIDENCE");
        when(profileMapper.getByDocumentIdForUpdate(2L,7L)).thenReturn(identity);
        when(versionMapper.maxVersionNo(5L)).thenReturn(3);
        doAnswer(invocation -> { ((SpaceKnowledgeProfileVersion) invocation.getArgument(0)).setId(12L); return 1; })
                .when(versionMapper).insert(any());

        SpaceKnowledgeProfileVersion version = service.createVersion(candidate,List.of("question"),
                "LLM_GENERATED","model-v2","prompt-v3",null,"candidate");

        assertEquals("NEEDS_REVIEW",version.getAssetState());
        assertEquals(new BigDecimal("0.6400"),version.getConfidence());
        assertEquals("LOW_CONFIDENCE",version.getConflictReason());
        verify(versionMapper,never()).supersedeActive(any(),any());
        verify(profileMapper).updateLatestVersionRef(eq(5L),eq(12L),eq("NEEDS_REVIEW"),
                eq(new BigDecimal("0.6400")),eq("LOW_CONFIDENCE"),any());
    }

    @Test
    void reviewAtomicallySupersedesCurrentAndActivatesLatestCandidate() {
        SpaceKnowledgeDocumentProfile current = profile("VALID",BigDecimal.valueOf(91));
        current.setId(5L);
        current.setCurrentVersionId(8L);
        current.setLatestVersionId(12L);
        SpaceKnowledgeProfileVersion candidate = new SpaceKnowledgeProfileVersion();
        candidate.setId(12L);
        candidate.setProfileId(5L);
        candidate.setAssetState("NEEDS_REVIEW");
        candidate.setConfidence(new BigDecimal("0.6400"));
        candidate.setProfileSnapshot("{\"title\":\"reviewed\",\"summary\":\"approved\",\"category\":\"engineering\",\"tags\":[\"java\"],\"keywords\":[\"quality\"],\"questions\":[\"Why?\"],\"qualityScore\":64,\"profileStatus\":\"NEEDS_REVIEW\",\"reviewStatus\":\"PENDING\"}");
        when(profileMapper.getByDocumentIdForUpdate(2L,7L)).thenReturn(current);
        when(versionMapper.getById(12L)).thenReturn(candidate);
        when(versionMapper.activateReviewed(eq(12L),eq(9L),any())).thenReturn(1);
        when(profileMapper.getByDocumentId(2L,7L)).thenReturn(current);

        service.activateVersion(2L,7L,12L,9L);

        verify(versionMapper).supersedeActive(eq(5L),any());
        verify(versionMapper).activateReviewed(eq(12L),eq(9L),any());
        verify(profileMapper).restoreFromVersion(argThat(profile ->
                "VALID".equals(profile.getProfileStatus()) && Long.valueOf(12L).equals(profile.getCurrentVersionId())));
        verify(questionMapper).deleteByDocumentId(2L,7L);
        verify(questionMapper).insert(argThat(question -> "Why?".equals(question.getQuestion())));
        verify(auditMapper).insert(argThat(audit -> "PROFILE_APPROVE".equals(audit.getAction())));
    }

    private SpaceKnowledgeDocumentProfile profile(String status,BigDecimal quality) {
        SpaceKnowledgeDocumentProfile profile = new SpaceKnowledgeDocumentProfile();
        profile.setSpaceId(2L);
        profile.setDocumentId(7L);
        profile.setSpaceFileId(4L);
        profile.setTitle("title");
        profile.setSummary("summary");
        profile.setKeywordsJson("[]");
        profile.setTagsJson("[]");
        profile.setProfileStatus(status);
        profile.setQualityScore(quality);
        profile.setSourceFileHash("hash-v1");
        profile.setSourceParserVersion("parser-v2");
        profile.setProfileSchemaVersion("profile-v1");
        return profile;
    }
}
