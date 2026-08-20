package com.ylcloud.service;

import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceMapper;
import com.ylcloud.mapper.SpaceMemberMapper;
import com.ylcloud.mapper.SpaceRagChunkRefMapper;
import com.ylcloud.mapper.SpaceRagDocumentMapper;
import com.ylcloud.service.rag.QdrantVectorStoreService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpaceDissolutionDataCleanupServiceTest {
    @Test
    void releasesLogicalReferencesAndOnlyDeletesUnsharedPhysicalFile() {
        SpaceFileMapper files = mock(SpaceFileMapper.class);
        FileInfoMapper fileInfo = mock(FileInfoMapper.class);
        PhysicalFileCleanupService physical = mock(PhysicalFileCleanupService.class);
        SpaceMapper spaces = mock(SpaceMapper.class);
        SpaceMemberMapper members = mock(SpaceMemberMapper.class);
        SpaceRagChunkRefMapper refs = mock(SpaceRagChunkRefMapper.class);
        SpaceRagDocumentMapper documents = mock(SpaceRagDocumentMapper.class);
        SpaceDissolutionDataCleanupService service = new SpaceDissolutionDataCleanupService(
                files,fileInfo,physical,mock(QdrantVectorStoreService.class),refs,documents,members,spaces);
        when(files.listActiveFileUuidsForCleanup(16L)).thenReturn(List.of("shared","owned"));
        when(files.disableAllByFileUuid(any(),any(),any())).thenReturn(1);
        when(fileInfo.updateFileCount(any(),any())).thenReturn(1);
        when(fileInfo.getFileCount("shared")).thenReturn(1);
        when(fileInfo.getFileCount("owned")).thenReturn(0);
        when(spaces.markDissolved(any(),any())).thenReturn(1);

        service.releaseReferencesAndFinalize(16L);

        verify(physical,never()).enqueue("shared");
        verify(physical).enqueue("owned");
        verify(refs).disableBySpaceId(any(),any());
        verify(documents).purgeBySpaceId(any(),any());
        verify(files).purgeBySpaceId(any(),any());
        verify(members).disableAllBySpaceId(any(),any());
        verify(spaces).markDissolved(any(),any());
    }
}
