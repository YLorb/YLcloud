package com.ylcloud.service;

import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.VO.FileVO;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.CrossStoreOperation;
import com.ylcloud.mapper.FileInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileDirectoryIdempotencyTest {
    private final FileInfoMapper files = mock(FileInfoMapper.class);
    private final CrossStoreOperationService operations = mock(CrossStoreOperationService.class);
    private final FileService service = new FileService();

    FileDirectoryIdempotencyTest() {
        ReflectionTestUtils.setField(service,"fileInfoMapper",files);
        ReflectionTestUtils.setField(service,"authorizationService",mock(AuthorizationService.class));
        service.setCrossStoreOperationService(operations);
    }

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    @Test
    void completedDirectoryCreationIsReplayedForTheSameIdempotencyKey() {
        BaseContext.setCurrentId(7L);
        UserFileDTO parent = UserFileDTO.builder().id(1L).userId(7L).parentId(0L)
                .fileUuid("root").fileName("/").Dir(1).status(1).build();
        UserFileDTO directory = UserFileDTO.builder().id(44L).userId(7L).parentId(1L)
                .fileUuid("directory").fileName("reports").Dir(1).status(1).build();
        CrossStoreOperation completed = new CrossStoreOperation();
        completed.setOperationStatus(CrossStoreOperationService.SUCCESS);
        completed.setResultRef("44");
        when(files.ParentIdExist(1L,7L)).thenReturn(true);
        when(files.getByFileIdAny(1L)).thenReturn(parent);
        when(files.getByFileIdAny(44L)).thenReturn(directory);
        when(operations.claim(anyString(),anyString(),anyString(),anyString())).thenReturn(completed);

        FileVO first = service.makefile(1,1L,"reports",null,"idem-directory-123");
        FileVO second = service.makefile(1,1L,"reports",null,"idem-directory-123");

        assertEquals(44L,first.getFileId());
        assertEquals(first.getFileId(),second.getFileId());
        verify(files,never()).insertFile_User(any());
        verify(operations,never()).recordResultCandidate(anyString(),anyString());
    }
}
