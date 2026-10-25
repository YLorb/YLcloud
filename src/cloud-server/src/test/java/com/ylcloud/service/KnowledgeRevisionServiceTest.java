package com.ylcloud.service;

import com.ylcloud.entity.*;
import com.ylcloud.mapper.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeRevisionServiceTest {
    final KnowledgeRevisionMapper revisions=mock(KnowledgeRevisionMapper.class);
    final KnowledgeReindexIntentMapper intents=mock(KnowledgeReindexIntentMapper.class);
    final SpaceFileMapper files=mock(SpaceFileMapper.class);
    final SpaceRagMapper configs=mock(SpaceRagMapper.class);
    final SpaceRagDocumentMapper documents=mock(SpaceRagDocumentMapper.class);
    final FileVersionMapper versions=mock(FileVersionMapper.class);
    final KnowledgeRevisionService service=new KnowledgeRevisionService(revisions,intents,files,configs,documents,versions,mock(InitialFileVersionService.class));

    @Test void defaultPolicyRefusesHistoricalAndTransitionUse() {
        setup(); KnowledgeRevision old=old();
        when(revisions.find(1L,2L,"old")).thenReturn(old);
        assertNull(service.safeRevision(1L,2L,"old",true));
        assertNull(service.safeRevision(1L,2L,"old",false));
    }

    @Test void transitionAllowsOnlyLatestCompletedSnapshotEvenWhileIndexing() {
        SpaceRagConfig config=setup(); config.setAllowStaleDuringReindex(true);
        SpaceFile node=files.getById(1L,2L); node.setSearchable(0); node.setKnowledgeState("INDEXING");
        KnowledgeRevision old=old();
        when(revisions.find(1L,2L,"old")).thenReturn(old);
        when(revisions.listBySpace(1L)).thenReturn(List.of(old));
        assertSame(old,service.safeRevision(1L,2L,"old",true));
        KnowledgeRevision newer=old(); newer.setId(99L);
        when(revisions.listBySpace(1L)).thenReturn(List.of(newer,old));
        assertNull(service.safeRevision(1L,2L,"old",true));
    }

    @Test void retentionUsesCurrentConfigurationAndExactRevisionId() {
        SpaceRagConfig config=setup(); config.setHistoricalEvidenceEnabled(true); config.setHistoryRetentionDays(7);
        KnowledgeRevision old=old(); old.setSupersededAt(LocalDateTime.now().minusDays(3));
        when(revisions.byId(1L,2L,3L)).thenReturn(old);
        assertSame(old,service.safeRevisionById(1L,2L,3L,false));
        config.setHistoryRetentionDays(1);
        assertNull(service.safeRevisionById(1L,2L,3L,false));
        config.setHistoryRetentionDays(7); config.setHistoricalEvidenceEnabled(false);
        assertNull(service.safeRevisionById(1L,2L,3L,false));
    }

    @Test void removingKnowledgeBlocksHistoricalReadRegardlessOfRetention() {
        SpaceRagConfig config=setup(); config.setHistoricalEvidenceEnabled(true); config.setHistoryRetentionDays(30);
        when(revisions.byId(1L,2L,3L)).thenReturn(old());
        files.getById(1L,2L).setKnowledgeState("REMOVAL_PENDING");
        assertNull(service.safeRevisionById(1L,2L,3L,false));
    }

    @Test void currentSnapshotRequiresHealthyPublishedDocument() {
        setup(); KnowledgeRevision current=old(); current.setSupersededAt(null); current.setFileHash("new");
        when(revisions.current(1L,2L)).thenReturn(current); when(revisions.byId(1L,2L,3L)).thenReturn(current);
        SpaceRagDocument document=new SpaceRagDocument(); document.setFileHash("new"); document.setIndexStatus("SUCCESS"); document.setVectorState("ACTIVE");
        when(documents.getBySpaceFileId(1L,2L)).thenReturn(document);
        assertSame(current,service.safeRevisionById(1L,2L,3L,true));
        document.setVectorState("CLEANUP_PENDING");
        assertNull(service.safeRevisionById(1L,2L,3L,true));
    }

    @Test void replacementInvalidatesTaskAndReferencesBeforeDurableDispatch() {
        setup(); service.queueReindex(1L,2L,8L);
        var order=inOrder(revisions,intents);
        order.verify(revisions).supersede(eq(1L),eq(2L),any(),isNull());
        order.verify(revisions).invalidateIndex(eq(1L),eq(2L),any());
        order.verify(revisions).invalidateRefs(eq(1L),eq(2L),any());
        order.verify(intents).enqueue(eq(1L),eq(2L),eq("new"),eq(8L),any());
    }

    @Test void metadataMismatchCannotPublishSnapshot() {
        setup(); FileRagChunk chunk=new FileRagChunk(); chunk.setId(9L); chunk.setFileUuid("uuid"); chunk.setFileHash("old");
        assertThrows(com.ylcloud.Exception.ConflictException.class,()->service.recordPublication(1L,2L,4L,List.of(chunk)));
        verify(revisions,never()).insert(any());
    }

    @Test void healthyLegacyDocumentWithoutPinnedSourceMustRebuildBeforeBootstrap() {
        setup(); SpaceRagDocument document=new SpaceRagDocument(); document.setId(4L); document.setFileHash("new");
        document.setIndexStatus("SUCCESS"); document.setVectorState("ACTIVE"); document.setChunkCount(1);
        when(documents.getBySpaceFileId(1L,2L)).thenReturn(document);
        when(documents.getAnyByIdForUpdate(4L)).thenReturn(document);
        FileRagChunk legacy=new FileRagChunk(); legacy.setId(8L); legacy.setFileUuid("uuid"); legacy.setFileHash("new");
        legacy.setMetadata("{\"parserVersion\":\"structured-v3\"}");
        when(revisions.activeDocumentChunksForUpdate(4L)).thenReturn(List.of(legacy));
        assertNull(service.snapshotCurrentNode(1L,2L));
        verify(revisions,never()).insert(any()); verifyNoInteractions(versions);
    }

    @Test void mixedSourceVersionsCannotPublishEvenWhenContentHashMatches() {
        setup(); FileRagChunk first=pinnedChunk(8L,"source-v1"),second=pinnedChunk(9L,"source-v2");
        FileVersion version=new FileVersion(); version.setId(5L); version.setFileUuid("uuid");
        version.setFileHash("new"); version.setMinioVersionId("source-v1");
        when(versions.findByObjectVersion("uuid","source-v1")).thenReturn(version);
        assertThrows(com.ylcloud.Exception.ConflictException.class,()->service.recordPublication(1L,2L,4L,List.of(first,second)));
        verify(revisions,never()).insert(any());
    }

    private FileRagChunk pinnedChunk(Long id,String version) {
        FileRagChunk chunk=new FileRagChunk(); chunk.setId(id); chunk.setFileUuid("uuid"); chunk.setFileHash("new");
        var source=com.ylcloud.service.rag.parser.ParsedDocument.success("uuid","new","tika","v3","content",List.of());
        source.setSourceMinioVersionId(version);
        chunk.setMetadata(com.ylcloud.service.rag.parser.ImmutableDocumentProvenance.stamp("{}",source)); return chunk;
    }

    private SpaceRagConfig setup() {
        SpaceFile node=new SpaceFile(); node.setId(2L); node.setSpaceId(1L); node.setFileUuid("uuid"); node.setContentHash("new");
        node.setStatus(1); node.setLifecycleState("ACTIVE"); node.setSearchable(1); node.setKnowledgeState("READY");
        when(files.getById(1L,2L)).thenReturn(node); when(files.lockById(1L,2L)).thenReturn(node);
        SpaceRagConfig config=new SpaceRagConfig(); config.setEnabled(1); config.setAllowStaleDuringReindex(false); config.setHistoricalEvidenceEnabled(false);
        when(configs.getBySpaceId(1L)).thenReturn(config); return config;
    }
    private KnowledgeRevision old() {
        KnowledgeRevision r=new KnowledgeRevision(); r.setId(3L); r.setSpaceId(1L); r.setSpaceFileId(2L);
        r.setFileUuid("uuid"); r.setFileHash("old"); r.setSupersededAt(LocalDateTime.now().minusHours(1)); return r;
    }
}
