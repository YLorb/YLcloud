package com.ylcloud.service;

import com.ylcloud.Exception.ConflictException;
import com.ylcloud.entity.UploadChunk;
import com.ylcloud.mapper.ChunkUploadMapper;
import com.ylcloud.mapper.MultifileMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChunkUploadLeaseServiceTest {
    private final ChunkUploadMapper chunkMapper = mock(ChunkUploadMapper.class);
    private final MultifileMapper multifileMapper = mock(MultifileMapper.class);
    private final ChunkUploadLeaseService service = new ChunkUploadLeaseService(chunkMapper,multifileMapper,3600);

    @Test
    void returnsCompletedForAnIdenticalFinishedChunk() {
        UploadChunk current = chunk("md5",5L,"chunks/file/0",1);
        when(chunkMapper.getAnyByUploadIdAndIndex("upload",0)).thenReturn(current);

        ChunkUploadLeaseService.Lease lease = service.reserve("upload",0,"md5",5L,"chunks/file/0");

        assertTrue(lease.completed());
        verify(chunkMapper,never()).claim(anyString(),anyInt(),anyString(),any(),any());
    }

    @Test
    void rejectsDifferentContentForTheSameChunkIndex() {
        when(chunkMapper.getAnyByUploadIdAndIndex("upload",0))
                .thenReturn(chunk("other",5L,"chunks/file/0",0));

        assertThrows(ConflictException.class,
                () -> service.reserve("upload",0,"md5",5L,"chunks/file/0"));
    }

    @Test
    void completesChunkAndCounterInOneTransactionBoundary() {
        when(chunkMapper.markComplete(eq("upload"),eq(0),eq("token"),any())).thenReturn(1);
        when(multifileMapper.increaseUploadedChunks("upload")).thenReturn(1);

        service.complete("upload",0,"token");

        verify(multifileMapper).increaseUploadedChunks("upload");
    }

    private UploadChunk chunk(String md5, Long size, String objectName, int status) {
        UploadChunk chunk = new UploadChunk();
        chunk.setChunkMd5(md5);
        chunk.setSize(size);
        chunk.setObjectName(objectName);
        chunk.setStatus(status);
        return chunk;
    }
}
