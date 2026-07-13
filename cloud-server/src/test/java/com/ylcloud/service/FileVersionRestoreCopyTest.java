package com.ylcloud.service;

import com.ylcloud.entity.FileVersion;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.FileVersionMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileVersionRestoreCopyTest {
    private final SpaceFileMapper spaceFileMapper = mock(SpaceFileMapper.class);
    private final FileVersionService service = new FileVersionService(
            mock(FileVersionMapper.class),
            mock(FileInfoMapper.class),
            spaceFileMapper,
            mock(SpacePermissionService.class),
            mock(SpaceFileService.class),
            mock(SpaceRagService.class),
            mock(MinioclientUtil.class),
            mock(SiteSettingService.class),
            mock(CrossStoreFileWriteService.class),
            mock(CrossStoreOperationService.class));

    @Test
    void exactCurrentVersionIsRestoredAsCopy() {
        SpaceFile node = node("report.pdf");
        FileVersion current = version("report.pdf","hash","md5",100L);
        FileVersion source = version("report.pdf","hash","md5",100L);

        Boolean exact = ReflectionTestUtils.invokeMethod(service,"isExactRestoreTarget",node,current,source);

        assertTrue(Boolean.TRUE.equals(exact));
    }

    @Test
    void incrementsCopySuffixBeforeExtension() {
        SpaceFile node = node("report.pdf");
        when(spaceFileMapper.countSameName(9L,3L,"report-副本(1).pdf",0)).thenReturn(1);
        when(spaceFileMapper.countSameName(9L,3L,"report-副本(2).pdf",0)).thenReturn(0);

        String name = ReflectionTestUtils.invokeMethod(service,"resolveCopySpaceName",node,"report.pdf");

        assertEquals("report-副本(2).pdf",name);
    }

    private SpaceFile node(String name) {
        SpaceFile node = new SpaceFile();
        node.setSpaceId(9L);
        node.setParentId(3L);
        node.setFileName(name);
        return node;
    }

    private FileVersion version(String name, String hash, String md5, Long size) {
        FileVersion version = new FileVersion();
        version.setFileName(name);
        version.setFileHash(hash);
        version.setFileMd5(md5);
        version.setFileSize(size);
        return version;
    }
}
