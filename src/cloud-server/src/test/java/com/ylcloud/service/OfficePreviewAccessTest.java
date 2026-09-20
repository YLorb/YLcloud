package com.ylcloud.service;

import com.ylcloud.DTO.UserFileDTO;
import com.ylcloud.Exception.ForbiddenException;
import com.ylcloud.authorization.ResourceAction;
import com.ylcloud.authorization.ResourceType;
import com.ylcloud.authorization.SpaceFileAction;
import com.ylcloud.context.BaseContext;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.service.space.SpaceFileAccessService;
import com.ylcloud.service.space.SpaceFileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OfficePreviewAccessTest {
    private final OfficePreviewService converter = mock(OfficePreviewService.class);
    private final FileInfoMapper files = mock(FileInfoMapper.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final SpaceFileAccessService spaceAccess = mock(SpaceFileAccessService.class);

    @AfterEach
    void clearContext() { BaseContext.removeCurrentId(); }

    private FileService personal() {
        BaseContext.setCurrentId(7L);
        FileService service = new FileService();
        ReflectionTestUtils.setField(service, "fileInfoMapper", files);
        ReflectionTestUtils.setField(service, "authorizationService", authorization);
        ReflectionTestUtils.setField(service, "officePreviewService", converter);
        UserFileDTO node = UserFileDTO.builder().build();
        node.setUserId(7L); node.setDir(0); node.setStatus(1);
        node.setFileUuid("office-id"); node.setFileName("report.docx");
        when(files.getByFileUuidAny("office-id", 9L)).thenReturn(node);
        when(files.getFileStatus("office-id")).thenReturn(true);
        metadata();
        return service;
    }

    private SpaceFileService space() {
        SpaceFileService service = mock(SpaceFileService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "spaceFileAccessService", spaceAccess);
        ReflectionTestUtils.setField(service, "fileInfoMapper", files);
        ReflectionTestUtils.setField(service, "officePreviewService", converter);
        SpaceFile node = new SpaceFile();
        node.setDir(0); node.setFileUuid("office-id"); node.setFileName("report.docx"); node.setCreatedBy(7L);
        when(spaceAccess.requireNodeAction(2L, 3L, 7L, SpaceFileAction.READ)).thenReturn(node);
        metadata();
        return service;
    }

    private void metadata() {
        File file = new File(); file.setFileUuid("office-id"); file.setType("docx"); file.setSize(123L);
        when(files.getFileByFileUuid("office-id", 7L)).thenReturn(file);
    }

    @Test
    void personalPreviewUsesAuthorizedStream() {
        FileService service = personal();
        assertEquals("office", service.previewFile("office-id", 9L).getPreviewType());
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.previewFileStream("office-id", 9L, response);
        verify(converter).writePdf("office-id", "report.docx", 123L, response);
        verify(authorization, times(2)).require(any(), eq(ResourceType.USER_PRIVATE), eq(7L), eq(ResourceAction.READ));
    }

    @Test
    void personalDenialPreventsMetadataAndConversion() {
        FileService service = personal();
        when(authorization.require(any(), any(), anyLong(), any())).thenThrow(new ForbiddenException("denied"));
        assertThrows(ForbiddenException.class, () -> service.previewFile("office-id", 9L));
        assertThrows(ForbiddenException.class, () -> service.previewFileStream("office-id", 9L, new MockHttpServletResponse()));
        verifyNoInteractions(converter);
        verify(files, never()).getFileByFileUuid(anyString(), anyLong());
    }

    @Test
    void spacePreviewUsesAuthorizedStream() {
        SpaceFileService service = space();
        assertEquals("/api/space/2/files/3/preview/stream", service.previewFile(2L, 3L, 7L).getPreviewUrl());
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.previewFileStream(2L, 3L, 7L, response);
        verify(converter).writePdf("office-id", "report.docx", 123L, response);
        verify(spaceAccess, times(2)).requireNodeAction(2L, 3L, 7L, SpaceFileAction.READ);
    }

    @Test
    void spaceDenialPreventsMetadataAndConversion() {
        SpaceFileService service = space();
        when(spaceAccess.requireNodeAction(2L, 3L, 7L, SpaceFileAction.READ)).thenThrow(new ForbiddenException("denied"));
        assertThrows(ForbiddenException.class, () -> service.previewFile(2L, 3L, 7L));
        assertThrows(ForbiddenException.class, () -> service.previewFileStream(2L, 3L, 7L, new MockHttpServletResponse()));
        verifyNoInteractions(converter);
        verify(files, never()).getFileByFileUuid(anyString(), anyLong());
    }
}
