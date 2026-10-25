package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.KnowledgeRevision;
import com.ylcloud.entity.SpaceRagConfig;
import com.ylcloud.mapper.KnowledgeRevisionMapper;
import com.ylcloud.mapper.SpaceRagMapper;
import com.ylcloud.service.space.SpacePermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeRetentionServiceTest {
    private final SpacePermissionService permissions=mock(SpacePermissionService.class);
    private final SpaceRagMapper configs=mock(SpaceRagMapper.class);
    private final KnowledgeRevisionMapper revisions=mock(KnowledgeRevisionMapper.class);
    private final KnowledgeRevisionService readable=mock(KnowledgeRevisionService.class);
    private final KnowledgeRetentionService service=new KnowledgeRetentionService(permissions,configs,revisions,readable);
    private SpaceRagConfig config;
    @BeforeEach void setup() {
        config=new SpaceRagConfig(); config.setHistoricalEvidenceEnabled(true); config.setHistoryRetentionDays(90);
        config.setConfigRevision(3L); config.setAllowStaleDuringReindex(false);
        when(configs.getBySpaceId(1L)).thenReturn(config);
    }
    @Test void shorteningCountsOnlyReadableHistoryAndDoesNotMutate() {
        var older=revision(1L,45); var recent=revision(2L,2); var expired=revision(3L,100); var current=revision(4L,0); current.setSupersededAt(null);
        when(revisions.listBySpace(1L)).thenReturn(List.of(current,expired,recent,older));
        when(readable.safeRevisionById(1L,11L,1L,false)).thenReturn(older);
        when(readable.safeRevisionById(1L,11L,2L,false)).thenReturn(recent);
        var result=service.preview(1L,7L,true,30);
        assertEquals(2,result.getCurrentlyAccessible()); assertEquals(1,result.getAffectedRevisions()); assertEquals(3,result.getConfigRevision());
        verify(revisions).listBySpace(1L); verifyNoMoreInteractions(revisions);
    }
    @Test void disablingHistoryExcludesTransitionVersion() {
        config.setAllowStaleDuringReindex(true);
        var old=revision(1L,15); var transition=revision(2L,1);
        when(revisions.listBySpace(1L)).thenReturn(List.of(transition,old));
        when(revisions.current(1L,11L)).thenReturn(null);
        when(readable.safeRevisionById(1L,11L,1L,false)).thenReturn(old);
        var result=service.preview(1L,7L,false,null);
        assertEquals(1,result.getCurrentlyAccessible()); assertEquals(1,result.getAffectedRevisions());
        verify(readable,never()).safeRevisionById(1L,11L,2L,false);
    }
    @Test void disabledHistoryHasNoRightsToRemove() {
        config.setHistoricalEvidenceEnabled(false);
        assertEquals(0,service.preview(1L,7L,false,null).getAffectedRevisions());
        verifyNoInteractions(revisions,readable);
    }
    @Test void memberCannotPreviewEvenCounts() {
        doThrow(new BaseException(403,"无权修改")).when(permissions).requireAdmin(1L,8L);
        assertThrows(BaseException.class,()->service.preview(1L,8L,false,null));
        verifyNoInteractions(configs,revisions,readable);
    }
    @Test void invalidRetentionFailsBeforeDatabaseReads() {
        assertThrows(BaseException.class,()->service.preview(1L,7L,true,null));
        assertThrows(BaseException.class,()->service.preview(1L,7L,true,0));
        verifyNoInteractions(configs,revisions,readable);
    }
    private KnowledgeRevision revision(long id,int daysAgo) {
        var revision=new KnowledgeRevision(); revision.setId(id); revision.setSpaceId(1L); revision.setSpaceFileId(11L);
        revision.setSupersededAt(LocalDateTime.now().minusDays(daysAgo)); return revision;
    }
}
