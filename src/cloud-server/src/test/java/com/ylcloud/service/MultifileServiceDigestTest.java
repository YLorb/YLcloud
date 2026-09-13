package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.constant.UploadTaskConstant;
import com.ylcloud.entity.UploadTask;
import com.ylcloud.mapper.ChunkUploadMapper;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.MultifileMapper;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultifileServiceDigestTest {
    @Test
    void digestMismatchCompensatesComposedObjectBeforeMetadataWrite() throws Exception {
        MultifileMapper taskMapper = mock(MultifileMapper.class);
        ChunkUploadMapper chunkMapper = mock(ChunkUploadMapper.class);
        FileInfoMapper fileMapper = mock(FileInfoMapper.class);
        MinioclientUtil minio = mock(MinioclientUtil.class);
        FileService fileService = mock(FileService.class);
        MultipartUploadCleanupService cleanupService = mock(MultipartUploadCleanupService.class);
        StorageService storageService = mock(StorageService.class);
        MultifileService service = new MultifileService();
        ReflectionTestUtils.setField(service,"multifileMapper",taskMapper);
        ReflectionTestUtils.setField(service,"chunkUploadMapper",chunkMapper);
        ReflectionTestUtils.setField(service,"fileInfoMapper",fileMapper);
        ReflectionTestUtils.setField(service,"minioclientUtil",minio);
        ReflectionTestUtils.setField(service,"fileService",fileService);
        ReflectionTestUtils.setField(service,"multipartUploadCleanupService",cleanupService);
        ReflectionTestUtils.setField(service,"storageService",storageService);

        UploadTask task = task();
        when(taskMapper.getByUploadId("upload-1",7L)).thenReturn(task);
        when(chunkMapper.listUploadedIndexes("upload-1")).thenReturn(List.of(0));
        when(fileService.getRootId(7L)).thenReturn(10L);
        when(taskMapper.claimMerge("upload-1",7L)).thenReturn(1);
        when(chunkMapper.listObjectNames("upload-1")).thenReturn(List.of("chunks/file-1/0"));
        when(minio.objectMatchesSize("file-1",5L)).thenReturn(false);
        when(minio.calculateObjectDigests("file-1")).thenReturn(new MinioclientUtil.ObjectDigests(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                task.getFileSha1(),task.getFileHash()));
        when(minio.removeObjectAllVersions("file-1")).thenReturn(1);

        BaseException error = assertThrows(BaseException.class,() -> service.merge("upload-1",7L));

        assertEquals("合并文件摘要校验失败",error.getMessage());
        verify(minio).mergeFileParts(any());
        verify(minio).removeObjectAllVersions("file-1");
        verify(fileMapper,never()).insertFileInfo(any());
        verify(taskMapper,never()).markMerged(any(),any());
        verify(cleanupService,never()).cleanupMergedTask(any(),any());
    }

    private UploadTask task() {
        UploadTask task = new UploadTask();
        task.setId(1L);
        task.setUploadId("upload-1");
        task.setUserId(7L);
        task.setParentId(0L);
        task.setFileName("fault.bin");
        task.setFileSize(5L);
        task.setFileMd5("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        task.setFileSha1("1111111111111111111111111111111111111111");
        task.setFileHash("2222222222222222222222222222222222222222222222222222222222222222");
        task.setTotalChunks(1);
        task.setStatus(UploadTaskConstant.UPLOADING);
        task.setFileUuid("file-1");
        return task;
    }
}
