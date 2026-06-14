package com.ylcloud.service.rag;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDocumentTextExtractorTest {

    @Test
    void extractTxtFromMinio() throws Exception {
        MinioclientUtil minioclientUtil = mock(MinioclientUtil.class);
        RagProperties properties = new RagProperties();
        DefaultDocumentTextExtractor extractor = new DefaultDocumentTextExtractor(minioclientUtil,properties);
        SpaceFile spaceFile = spaceFile("notes.txt");
        File file = file("file-1","notes.txt",100L);
        when(minioclientUtil.getObjectStream("file-1"))
                .thenReturn(new ByteArrayInputStream("hello rag".getBytes(StandardCharsets.UTF_8)));

        ExtractedDocumentText result = extractor.extract(spaceFile,file);

        assertTrue(result.isSuccess());
        assertFalse(result.isFallback());
        assertEquals("plain-text",result.getParser());
        assertEquals("hello rag",result.getText());
    }

    @Test
    void unsupportedExtensionFallsBackWithoutReadingMinio() throws Exception {
        MinioclientUtil minioclientUtil = mock(MinioclientUtil.class);
        DefaultDocumentTextExtractor extractor = new DefaultDocumentTextExtractor(minioclientUtil,new RagProperties());

        ExtractedDocumentText result = extractor.extract(spaceFile("video.mp4"),file("file-1","video.mp4",100L));

        assertFalse(result.isSuccess());
        assertTrue(result.isFallback());
        assertEquals("暂不支持该文件类型",result.getErrorMessage());
        verify(minioclientUtil,never()).getObjectStream("file-1");
    }

    @Test
    void oversizedFileFallsBackWithoutReadingMinio() throws Exception {
        MinioclientUtil minioclientUtil = mock(MinioclientUtil.class);
        RagProperties properties = new RagProperties();
        properties.getExtraction().setMaxFileSize(10L);
        DefaultDocumentTextExtractor extractor = new DefaultDocumentTextExtractor(minioclientUtil,properties);

        ExtractedDocumentText result = extractor.extract(spaceFile("large.pdf"),file("file-1","large.pdf",100L));

        assertFalse(result.isSuccess());
        assertTrue(result.isFallback());
        assertEquals("文件超过正文解析大小限制",result.getErrorMessage());
        verify(minioclientUtil,never()).getObjectStream("file-1");
    }

    private SpaceFile spaceFile(String fileName) {
        SpaceFile spaceFile = new SpaceFile();
        spaceFile.setFileName(fileName);
        spaceFile.setFileUuid("file-1");
        return spaceFile;
    }

    private File file(String fileUuid, String name, Long size) {
        File file = new File();
        file.setFileUuid(fileUuid);
        file.setName(name);
        file.setSize(size);
        return file;
    }
}
