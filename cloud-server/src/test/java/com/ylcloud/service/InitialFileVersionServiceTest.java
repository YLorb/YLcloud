package com.ylcloud.service;

import com.ylcloud.Exception.ConflictException;
import com.ylcloud.entity.File;
import com.ylcloud.entity.FileVersion;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InitialFileVersionServiceTest {
    @Test
    void createsV1FromCurrentMinioObject() throws Exception {
        Fixture fixture = new Fixture();
        File file = File.builder()
                .fileUuid("file-1")
                .name("physical.txt")
                .type("txt")
                .size(12L)
                .hash("sha256")
                .md5("md5")
                .build();
        when(fixture.versionMapper.getMaxVersionNo("file-1")).thenReturn(0);
        when(fixture.fileInfoMapper.getFileInfo("file-1",7L)).thenReturn(file);
        when(fixture.minio.isDefaultBucketVersioningEnabled()).thenReturn(true);
        when(fixture.minio.getCurrentObjectVersionId("file-1")).thenReturn("minio-v1");

        fixture.service.ensureInitialVersion("file-1","display.txt",7L);

        ArgumentCaptor<FileVersion> captor = ArgumentCaptor.forClass(FileVersion.class);
        verify(fixture.versionMapper).insertInitial(captor.capture());
        FileVersion version = captor.getValue();
        assertEquals("file-1",version.getFileUuid());
        assertEquals("minio-v1",version.getMinioVersionId());
        assertEquals("display.txt",version.getFileName());
        assertEquals("初始版本",version.getChangeNote());
        assertEquals(7L,version.getCreatedBy());
    }

    @Test
    void isIdempotentWhenVersionAlreadyExists() {
        Fixture fixture = new Fixture();
        when(fixture.versionMapper.getMaxVersionNo("file-1")).thenReturn(1);

        fixture.service.ensureInitialVersion("file-1","display.txt",7L);

        verify(fixture.fileInfoMapper,never()).getFileInfo("file-1",7L);
        verify(fixture.versionMapper,never()).insertInitial(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsObjectWithoutVersionId() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.versionMapper.getMaxVersionNo("file-1")).thenReturn(0);
        when(fixture.fileInfoMapper.getFileInfo("file-1",7L)).thenReturn(File.builder().fileUuid("file-1").name("a.txt").build());
        when(fixture.minio.isDefaultBucketVersioningEnabled()).thenReturn(true);
        when(fixture.minio.getCurrentObjectVersionId("file-1")).thenReturn(null);

        assertThrows(ConflictException.class,
                () -> fixture.service.ensureInitialVersion("file-1","a.txt",7L));
    }

    private static class Fixture {
        final FileVersionMapper versionMapper = mock(FileVersionMapper.class);
        final FileInfoMapper fileInfoMapper = mock(FileInfoMapper.class);
        final MinioclientUtil minio = mock(MinioclientUtil.class);
        final InitialFileVersionService service = new InitialFileVersionService(versionMapper,fileInfoMapper,minio);
    }
}
