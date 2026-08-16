package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.SpaceFileLifecycleMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpaceFileLifecycleServiceTest {
    private final SpaceFileLifecycleMapper mapper = mock(SpaceFileLifecycleMapper.class);
    private final SpaceFileLifecycleService service = new SpaceFileLifecycleService(mapper,new ObjectMapper());

    @Test
    void addingFileCreatesReferenceAndVersionedIndexEvent() {
        SpaceFile file = file("INDEX_PENDING",null);
        SpaceFile current = file("INDEX_PENDING",1L);
        when(mapper.beginIndex(eq(2L),eq(7L),any())).thenReturn(1);
        when(mapper.getAny(2L,7L)).thenReturn(current);

        assertEquals(1L,service.fileAdded(file,9L));

        verify(mapper).addReference(eq("file-uuid"),eq(7L),eq(9L),eq(2L),any());
        verify(mapper).insertEvent(anyString(),eq("INDEX_REQUESTED:7:1"),eq("INDEX_REQUESTED"),
                eq(2L),eq(7L),eq("file-uuid"),eq(1L),contains("\"resourceVersion\":1"),any());
    }

    @Test
    void removalImmediatelyReleasesSpaceReferenceAndCreatesNewVersion() {
        SpaceFile file = file("READY",1L);
        SpaceFile current = file("REMOVAL_PENDING",2L);
        when(mapper.beginRemoval(eq(2L),eq(7L),any())).thenReturn(1);
        when(mapper.getAny(2L,7L)).thenReturn(current);

        assertEquals(2L,service.fileRemovalStarted(file));

        verify(mapper).supersedeIndexEvents(eq(7L),eq(2L),any());
        verify(mapper).releaseReference(eq(7L),any());
        verify(mapper).insertEvent(anyString(),eq("REMOVAL_REQUESTED:7:2"),eq("REMOVAL_REQUESTED"),
                eq(2L),eq(7L),eq("file-uuid"),eq(2L),anyString(),any());
    }

    @Test
    void rebuildingReadyFileBumpsVersionBeforeIndexing() {
        SpaceFile ready = file("READY",3L);
        SpaceFile pending = file("INDEX_PENDING",4L);
        when(mapper.getAny(2L,7L)).thenReturn(ready,pending);
        when(mapper.beginIndex(eq(2L),eq(7L),any())).thenReturn(1);

        service.indexing(2L,7L);

        verify(mapper).insertEvent(anyString(),eq("INDEX_REQUESTED:7:4"),eq("INDEX_REQUESTED"),
                eq(2L),eq(7L),eq("file-uuid"),eq(4L),anyString(),any());
        verify(mapper).markIndexing(eq(2L),eq(7L),any());
    }

    @Test
    void personalReferenceTracksCreateAndPermanentRemoval() {
        UserFileDTO file = UserFileDTO.builder()
                .id(41L)
                .userId(9L)
                .fileUuid("physical-41")
                .Dir(0)
                .build();

        service.personalFileAdded(file);
        service.personalFileRemoved(file);

        verify(mapper).addUserReference(eq("physical-41"),eq(41L),eq(9L),any());
        verify(mapper).releaseUserReference(eq(41L),any());
    }

    private SpaceFile file(String state,Long version) {
        SpaceFile file = new SpaceFile();
        file.setId(7L);
        file.setSpaceId(2L);
        file.setFileUuid("file-uuid");
        file.setDir(0);
        file.setKnowledgeState(state);
        file.setKnowledgeVersion(version);
        return file;
    }
}
