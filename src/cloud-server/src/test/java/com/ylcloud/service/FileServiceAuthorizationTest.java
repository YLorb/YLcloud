package com.ylcloud.service;

import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.authorization.AccessSubject;
import com.ylcloud.authorization.ResourceAction;
import com.ylcloud.authorization.ResourceType;
import com.ylcloud.context.BaseContext;
import com.ylcloud.mapper.FileInfoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileServiceAuthorizationTest {
    private final FileInfoMapper fileInfoMapper = mock(FileInfoMapper.class);
    private final AuthorizationService authorizationService = mock(AuthorizationService.class);
    private final FileService service = new FileService();

    FileServiceAuthorizationTest() {
        ReflectionTestUtils.setField(service,"fileInfoMapper",fileInfoMapper);
        ReflectionTestUtils.setField(service,"authorizationService",authorizationService);
    }

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    @Test
    void crossUserListCannotReachMapperWithoutUnifiedAuthorization() {
        BaseContext.setCurrentId(9L);
        when(authorizationService.require(
                AccessSubject.user(9L),ResourceType.USER_PRIVATE,20L,ResourceAction.READ
        )).thenThrow(new ForbiddenException("没有资源访问权限"));

        assertThrows(ForbiddenException.class,() -> service.listFiles(0L,20L));
        verify(fileInfoMapper,never()).getRootDirByUserId(20L);
    }

    @Test
    void readGrantCannotBeReusedToCreateFileShare() {
        BaseContext.setCurrentId(9L);
        UserFileDTO file = UserFileDTO.builder()
                .id(31L)
                .userId(20L)
                .parentId(22L)
                .fileUuid("file-uuid")
                .fileName("private.txt")
                .Dir(0)
                .status(1)
                .build();
        when(fileInfoMapper.getByFileUuidAny("file-uuid",22L)).thenReturn(file);
        when(authorizationService.require(
                AccessSubject.user(9L),ResourceType.USER_PRIVATE,20L,ResourceAction.WRITE
        )).thenThrow(new ForbiddenException("没有资源访问权限"));

        assertThrows(ForbiddenException.class,() -> service.shareFile("file-uuid",22L));
    }
}
