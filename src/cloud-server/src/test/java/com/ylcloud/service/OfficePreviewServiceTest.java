package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OfficePreviewServiceTest {
    private final MinioclientUtil minio = mock(MinioclientUtil.class);
    private final OfficePreviewService service = spy(new OfficePreviewService(minio, "libreoffice"));

    @Test
    void streamsPdfAndRemovesTemporaryFiles() throws Exception {
        when(minio.getObjectStream("id")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        AtomicReference<Path> directory = new AtomicReference<>();
        Process process = mock(Process.class);
        when(process.waitFor(60, TimeUnit.SECONDS)).thenReturn(true);
        doAnswer(call -> {
            Path work = call.getArgument(0);
            directory.set(work);
            assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(call.getArgument(1)));
            Files.writeString(work.resolve("document.pdf"), "%PDF-1.4\nfixture");
            return process;
        }).when(service).startConversion(any(), any());
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.writePdf("id", "中文报告.DOCX", null, response);
        assertEquals("application/pdf", response.getContentType());
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getContentAsString().startsWith("%PDF-"));
        assertFalse(Files.exists(directory.get()));
    }

    @Test
    void rejectsOversizedAndUnsupportedFilesBeforeReadingStorage() {
        assertThrows(BaseException.class, () -> service.writePdf("id", "a.docx", 51L * 1024 * 1024, new MockHttpServletResponse()));
        assertThrows(BaseException.class, () -> service.writePdf("id", "a.zip", 1L, new MockHttpServletResponse()));
        verifyNoInteractions(minio);
    }

    @Test
    void killsTimedOutProcessAndCleansDirectory() throws Exception {
        when(minio.getObjectStream("id")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        Process process = mock(Process.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.isAlive()).thenReturn(true);
        when(process.descendants()).thenReturn(Stream.of(child));
        AtomicReference<Path> directory = new AtomicReference<>();
        doAnswer(call -> { directory.set(call.getArgument(0)); return process; }).when(service).startConversion(any(), any());
        BaseException error = assertThrows(BaseException.class, () -> service.writePdf("id", "slides.pptx", 1L, new MockHttpServletResponse()));
        assertTrue(error.getMessage().contains("超时"));
        verify(process).destroyForcibly();
        verify(child).destroyForcibly();
        assertFalse(Files.exists(directory.get()));
    }

    @Test
    void doesNotSendPdfHeadersForFailedConversion() throws Exception {
        when(minio.getObjectStream("id")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        Process process = mock(Process.class);
        when(process.waitFor(60, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(1);
        doReturn(process).when(service).startConversion(any(), any());
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(BaseException.class, () -> service.writePdf("id", "budget.xlsx", 1L, response));
        assertNull(response.getContentType());
        assertEquals(0, response.getContentAsByteArray().length);
    }
}
