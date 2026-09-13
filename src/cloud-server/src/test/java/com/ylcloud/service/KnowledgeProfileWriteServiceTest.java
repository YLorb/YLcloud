package com.ylcloud.service;

import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;
import com.ylcloud.mapper.SpaceKnowledgeDocumentProfileMapper;
import com.ylcloud.mapper.SpaceKnowledgeQuestionMapper;
import com.ylcloud.service.knowledge.pipeline.KnowledgeSourceSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;

class KnowledgeProfileWriteServiceTest {
    @Test
    void reviewCandidateDoesNotReplaceCurrentEffectiveProfileOrQuestions() {
        SpaceKnowledgeDocumentProfileMapper profileMapper = mock(SpaceKnowledgeDocumentProfileMapper.class);
        SpaceKnowledgeQuestionMapper questionMapper = mock(SpaceKnowledgeQuestionMapper.class);
        KnowledgeProfileAssetService assetService = mock(KnowledgeProfileAssetService.class);
        SpaceKnowledgeDocumentProfile active = profile(1L,"active");
        active.setId(5L);
        active.setCurrentVersionId(10L);
        SpaceKnowledgeDocumentProfile candidate = profile(2L,"candidate");
        candidate.setSpaceId(2L);
        candidate.setDocumentId(4L);
        candidate.setProfileStatus("NEEDS_REVIEW");
        candidate.setRepairAttempt(0);
        when(profileMapper.getByDocumentIdForUpdate(2L,4L)).thenReturn(active);
        when(profileMapper.getByDocumentId(2L,4L)).thenReturn(active);

        new KnowledgeProfileWriteService(profileMapper,questionMapper,assetService)
                .saveProfile(candidate,java.util.List.of("candidate question"),"model-v1","prompt-v1");

        verify(profileMapper,never()).upsert(any());
        verify(questionMapper,never()).deleteByDocumentId(any(),any());
        verify(questionMapper,never()).insert(any());
        verify(assetService).createVersion(eq(candidate),anyList(),eq("LLM_GENERATED"),eq("model-v1"),
                eq("prompt-v1"),eq(null),anyString());
    }

    @Test
    void syncRetrievalSourceWritesChunkSnapshotWithExpectedRevision() {
        SpaceKnowledgeDocumentProfileMapper profileMapper = mock(SpaceKnowledgeDocumentProfileMapper.class);
        SpaceKnowledgeQuestionMapper questionMapper = mock(SpaceKnowledgeQuestionMapper.class);
        KnowledgeProfileAssetService assetService = mock(KnowledgeProfileAssetService.class);
        SpaceKnowledgeDocumentProfile current = profile(3L,"old-signature");
        SpaceKnowledgeDocumentProfile updated = profile(4L,"new-signature");
        KnowledgeSourceSnapshot snapshot = snapshot();
        when(profileMapper.getSourceSnapshotForUpdate(2L,4L)).thenReturn(current);
        when(profileMapper.getByDocumentId(2L,4L)).thenReturn(updated);
        when(profileMapper.syncRetrievalSource(eq(2L),eq(4L),eq("[\"11\",\"12\"]"),eq(2),eq(320),
                eq("structured-v2"),eq("new-signature"),eq(3L),any())).thenReturn(1);

        SpaceKnowledgeDocumentProfile result = new KnowledgeProfileWriteService(profileMapper,questionMapper,assetService)
                .syncRetrievalSource(2L,4L,snapshot,3L);

        assertSame(updated,result);
        verify(profileMapper).syncRetrievalSource(eq(2L),eq(4L),eq("[\"11\",\"12\"]"),eq(2),eq(320),
                eq("structured-v2"),eq("new-signature"),eq(3L),any());
    }

    @Test
    void syncRetrievalSourceIsIdempotentWhenSnapshotAlreadyMatches() {
        SpaceKnowledgeDocumentProfileMapper profileMapper = mock(SpaceKnowledgeDocumentProfileMapper.class);
        SpaceKnowledgeDocumentProfile current = profile(4L,"new-signature");
        when(profileMapper.getSourceSnapshotForUpdate(2L,4L)).thenReturn(current);
        when(profileMapper.getByDocumentId(2L,4L)).thenReturn(current);

        SpaceKnowledgeDocumentProfile result = new KnowledgeProfileWriteService(
                profileMapper,mock(SpaceKnowledgeQuestionMapper.class),mock(KnowledgeProfileAssetService.class))
                .syncRetrievalSource(2L,4L,snapshot(),3L);

        assertSame(current,result);
        verify(profileMapper,never()).syncRetrievalSource(any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void syncRetrievalSourceRejectsConcurrentDifferentSnapshot() {
        SpaceKnowledgeDocumentProfileMapper profileMapper = mock(SpaceKnowledgeDocumentProfileMapper.class);
        when(profileMapper.getSourceSnapshotForUpdate(2L,4L)).thenReturn(profile(4L,"other-signature"));

        KnowledgeProfileWriteService service = new KnowledgeProfileWriteService(
                profileMapper,mock(SpaceKnowledgeQuestionMapper.class),mock(KnowledgeProfileAssetService.class));

        assertThrows(com.ylcloud.Exception.ConflictException.class,
                () -> service.syncRetrievalSource(2L,4L,snapshot(),3L));
    }

    private KnowledgeSourceSnapshot snapshot() {
        return new KnowledgeSourceSnapshot("[\"11\",\"12\"]",2,320,"structured-v2","new-signature");
    }

    private SpaceKnowledgeDocumentProfile profile(Long revision, String signature) {
        SpaceKnowledgeDocumentProfile profile = new SpaceKnowledgeDocumentProfile();
        profile.setSourceChunkIds("[\"11\",\"12\"]");
        profile.setSourceChunkCount(2);
        profile.setSourceCharacterCount(320);
        profile.setSourceParserVersion("structured-v2");
        profile.setSourceSnapshotSignature(signature);
        profile.setSourceSnapshotRevision(revision);
        return profile;
    }
}
