package com.ylcloud.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.Exception.NotFoundException;
import com.ylcloud.VO.*;
import com.ylcloud.entity.*;
import com.ylcloud.mapper.RagEvidenceMapper;
import com.ylcloud.service.KnowledgeRevisionService;
import com.ylcloud.service.space.SpaceFileAccessService;
import com.ylcloud.service.space.SpacePermissionService;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagEvidenceServiceTest {
    RagEvidenceMapper mapper=mock(RagEvidenceMapper.class);
    KnowledgeRevisionService revisions=mock(KnowledgeRevisionService.class);
    SpaceFileAccessService access=mock(SpaceFileAccessService.class);
    SpacePermissionService permissions=mock(SpacePermissionService.class);
    MinioclientUtil objects=mock(MinioclientUtil.class);
    ObjectMapper json=new ObjectMapper();
    RagEvidenceService service=new RagEvidenceService(mapper,revisions,access,permissions,objects,json);
    KnowledgeRevision revision;
    FileRagChunk chunk;
    RagAnswerEvidence evidence;

    @BeforeEach void prepare() {
        revision=new KnowledgeRevision(); revision.setId(40L); revision.setSpaceId(1L); revision.setSpaceFileId(2L);
        revision.setFileUuid("object-v1"); revision.setFileHash("h1"); revision.setFileVersionId(4L); revision.setMinioVersionId("immutable-v1");
        when(revisions.revisionForChunk(1L,3L,true)).thenReturn(revision);
        when(revisions.safeRevisionById(1L,2L,40L,false)).thenReturn(revision);
        when(revisions.safeRevisionById(1L,2L,40L,true)).thenReturn(revision);
        SpaceFile file=new SpaceFile(); file.setId(2L); file.setFileName("reference.pdf");
        when(access.requireNodeAction(eq(1L),eq(2L),eq(7L),any())).thenReturn(file);
        chunk=new FileRagChunk(); chunk.setId(3L); chunk.setFileHash("h1"); chunk.setContent("supported text plus omitted text");
        chunk.setMetadata("{\"pageStart\":2,\"pageEnd\":3}");
        evidence=new RagAnswerEvidence(); evidence.setChunkId(3L); evidence.setContent("supported text"); evidence.setEvidenceId("chunk:3:hash");
    }

    @Test void capturesOnlyExactModelExcerptAndImmutableVersion() {
        List<SpaceRagCitationVO> result=service.createCitations(1L,7L,List.of(chunk),List.of(evidence));
        SpaceRagCitationVO citation=result.get(0);
        assertEquals(40L,citation.getRevisionId()); assertEquals(4L,citation.getFileVersionId());
        assertEquals(2,citation.getPageStart()); assertTrue(citation.getPreviewUrl().endsWith("#page=2"));
        assertNotEquals(evidence.getEvidenceId(),citation.getEvidenceId());
        ArgumentCaptor<RagEvidenceRecord> stored=ArgumentCaptor.forClass(RagEvidenceRecord.class);
        verify(mapper).insertEvidence(stored.capture());
        assertEquals("supported text",stored.getValue().getContent());
    }

    @Test void rejectsFabricatedOrUnsentExcerpt() {
        evidence.setContent("made up evidence");
        assertThrows(BaseException.class,()->service.createCitations(1L,7L,List.of(chunk),List.of(evidence)));
        verify(mapper,never()).insertEvidence(any());
    }

    @Test void changedRevisionBetweenGenerationAndSavingIsRejected() {
        when(revisions.revisionForChunk(1L,3L,true)).thenReturn(null);
        assertThrows(BaseException.class,()->service.createCitations(1L,7L,List.of(chunk),List.of(evidence)));
        verify(mapper,never()).insertEvidence(any());
    }

    @Test void everyReadRechecksCurrentAuthorizationAndExpiry() {
        RagEvidenceRecord record=record(); when(mapper.getEvidence("opaque",7L)).thenReturn(record);
        assertNotNull(service.require(1L,"opaque",7L,false));
        when(revisions.safeRevisionById(1L,2L,40L,false)).thenReturn(null);
        assertThrows(NotFoundException.class,()->service.require(1L,"opaque",7L,false));
        verify(access,times(2)).requireNodeAction(eq(1L),eq(2L),eq(7L),any());
    }

    @Test void crossSpaceAndDifferentUserCannotUseOpaqueId() {
        when(mapper.getEvidence("opaque",7L)).thenReturn(record());
        assertThrows(NotFoundException.class,()->service.require(99L,"opaque",7L,false));
        assertThrows(NotFoundException.class,()->service.require(1L,"opaque",8L,false));
        verifyNoInteractions(objects);
    }

    @Test void previewNeverFallsBackToLatestObjectWhenVersionMissing() throws Exception {
        RagEvidenceRecord record=record(); record.setCitationJson("{\"fileName\":\"ref.pdf\"}");
        when(mapper.getEvidence("opaque",7L)).thenReturn(record);
        revision.setMinioVersionId(null);
        assertThrows(NotFoundException.class,()->service.stream(1L,"opaque",7L,false,new MockHttpServletResponse()));
        verifyNoInteractions(objects);
    }

    @Test void legacyOrRevokedAssistantBodyAndCitationsAreHidden() {
        KnowledgeChatMessage message=new KnowledgeChatMessage(); message.setRole("assistant"); message.setUserId(7L);
        KnowledgeChatMessageVO vo=new KnowledgeChatMessageVO(); vo.setContent("sensitive old response"); vo.setCitationsJson("sensitive");
        service.protectMessage(message,vo);
        assertEquals("UNAVAILABLE",vo.getVerificationStatus()); assertEquals("[]",vo.getCitationsJson());
        assertFalse(vo.getContent().contains("sensitive"));
    }

    @Test void trustedAnswerReadUsesStoredServerContent() {
        RagTrustedAnswer answer=new RagTrustedAnswer(); answer.setId("answer"); answer.setUserId(7L);
        answer.setContent("verified"); answer.setCitationsJson("[]"); answer.setSpaceIdsJson("[1]");
        when(mapper.getAnswer("answer",7L)).thenReturn(answer);
        assertEquals("verified",service.readAnswer("answer",7L).getContent());
        verify(permissions).requireMember(1L,7L);
    }

    @Test void refusedHistoryIsNotMarkedAsVerifiedAnswer() {
        RagTrustedAnswer answer=new RagTrustedAnswer(); answer.setId("answer"); answer.setUserId(7L);
        answer.setContent("缺少政策依据，无法确认退款资格。"); answer.setCitationsJson("[]"); answer.setSpaceIdsJson("[1]");
        answer.setAnswerStatus("REFUSED");
        when(mapper.getAnswer("answer",7L)).thenReturn(answer);
        KnowledgeChatMessage message=new KnowledgeChatMessage(); message.setRole("assistant");
        message.setUserId(7L); message.setTrustedAnswerId("answer");
        KnowledgeChatMessageVO vo=new KnowledgeChatMessageVO(); service.protectMessage(message,vo);
        assertEquals("REFUSED",vo.getVerificationStatus()); assertEquals(answer.getContent(),vo.getContent());
    }

    @Test void failedAndCancelledTasksRetainTheirStatusWithoutExposingProviderErrors() {
        for(String status:List.of("FAILED","CANCELED")) {
            KnowledgeChatMessage message=new KnowledgeChatMessage(); message.setRole("assistant");
            message.setUserId(7L); message.setTaskStatus(status);
            KnowledgeChatMessageVO vo=new KnowledgeChatMessageVO(); vo.setErrorMessage("restricted source content");
            service.protectMessage(message,vo);
            assertEquals(status,vo.getVerificationStatus()); assertEquals("[]",vo.getCitationsJson());
            assertFalse(vo.getErrorMessage().contains("restricted"));
        }
    }

    private RagEvidenceRecord record() {
        RagEvidenceRecord record=new RagEvidenceRecord(); record.setId("opaque"); record.setUserId(7L);
        record.setSpaceId(1L); record.setSpaceFileId(2L); record.setRevisionId(40L); record.setFileHash("h1");
        return record;
    }
}
