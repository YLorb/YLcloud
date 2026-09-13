package com.ylcloud.service;

import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.mapper.FileInfoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class FileRestoreNamingTest {
    @Test
    void appendsCopySuffixBeforeExtensionWhenRestoreNameExists() {
        FileInfoMapper mapper = mock(FileInfoMapper.class);
        FileService service = new FileService();
        ReflectionTestUtils.setField(service,"fileInfoMapper",mapper);
        UserFileDTO file = UserFileDTO.builder()
                .id(8L)
                .fileName("report.pdf")
                .fileUuid("uuid")
                .Dir(0)
                .status(2)
                .userId(2L)
                .parentId(3L)
                .build();
        when(mapper.countActiveNameExcluding(2L,3L,"report.pdf",0,8L)).thenReturn(1);
        when(mapper.countActiveNameExcluding(2L,3L,"report-副本(1).pdf",0,8L)).thenReturn(0);

        String restored = ReflectionTestUtils.invokeMethod(service,"resolveRestoreName",file);

        assertEquals("report-副本(1).pdf",restored);
    }
}
