package com.ylcloud.service;

import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.*;
import com.ylcloud.service.space.*;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class FileVersionSharedIsolationTest {
    final FileInfoMapper physical=mock(FileInfoMapper.class);
    final SpaceFileMapper nodes=mock(SpaceFileMapper.class);
    final KnowledgeRevisionMapper revisions=mock(KnowledgeRevisionMapper.class);
    final FileVersionService service=new FileVersionService(mock(FileVersionMapper.class),physical,nodes,
            mock(SpacePermissionService.class),mock(SpaceFileAccessService.class),mock(SpaceFileService.class),
            mock(SpaceRagService.class),mock(MinioclientUtil.class),mock(SiteSettingService.class),
            mock(CrossStoreFileWriteService.class),mock(CrossStoreOperationService.class));

    @Test void replacementOfSharedFileOnlyRebindsOneLogicalNode() {
        service.setKnowledgeRevisions(mock(KnowledgeRevisionService.class),revisions);
        SpaceFile node=node();
        when(physical.insertFileInfo(any())).thenReturn(1);
        when(revisions.rebindPhysical(eq(1L),eq(2L),eq("shared"),eq("isolated"),any())).thenReturn(1);
        when(physical.updateFileCount("shared",-1)).thenReturn(1);
        ReflectionTestUtils.invokeMethod(service,"persistReplacement",node,"isolated","new.txt",".txt",9L,"md5","sha1","hash");
        verify(physical,never()).updatePhysicalFileInfo(eq("shared"),any(),any(),any(),any(),any(),any(),any());
        verify(revisions).rebindPhysicalReference(2L,"shared","isolated");
        verify(revisions).rebindDocumentPhysical(1L,2L,"isolated");
        assertEquals("isolated",node.getFileUuid());
    }

    @Test void staleLogicalBindingCannotReleaseSharedObjectReference() {
        service.setKnowledgeRevisions(mock(KnowledgeRevisionService.class),revisions);
        when(physical.insertFileInfo(any())).thenReturn(1);
        assertThrows(com.ylcloud.Exception.BaseException.class,()->ReflectionTestUtils.invokeMethod(service,
                "persistReplacement",node(),"isolated","new.txt",".txt",9L,"md5","sha1","hash"));
        verify(physical,never()).updateFileCount(any(),anyInt());
        verify(revisions,never()).rebindPhysicalReference(any(),any(),any());
    }
    private SpaceFile node() {
        SpaceFile node=new SpaceFile(); node.setId(2L); node.setSpaceId(1L); node.setFileUuid("shared"); return node;
    }
}
