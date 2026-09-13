package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.utils.MinioclientUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VlmPageParserTest {

    @Test
    void disabledVlmDoesNotCallParserService() {
        RagProperties properties = new RagProperties();
        properties.getExtraction().setVlmEnabled(false);
        ParserServiceClient client = mock(ParserServiceClient.class);
        VlmPageParser parser = new VlmPageParser(properties,client);

        ParsedDocument result = parser.parseFirstPage(spaceFile(),file());

        assertFalse(result.isSuccess());
        assertEquals("VLM parser is disabled",result.getErrorMessage());
        verify(client,never()).parseVlmPage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disabledOcrDoesNotCallParserService() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getExtraction().setOcrEnabled(false);
        ParserServiceClient client = mock(ParserServiceClient.class);
        MinioclientUtil minio = mock(MinioclientUtil.class);
        OcrStructuredParser parser = new OcrStructuredParser(properties,client,minio);

        ParsedDocument result = parser.parse(spaceFile(),file());

        assertFalse(result.isSuccess());
        assertEquals("OCR parser is disabled",result.getErrorMessage());
        verify(client,never()).parseOcr(org.mockito.ArgumentMatchers.any());
        verify(minio,never()).getPresignedObjectUrl(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void enabledOcrPassesPresignedUrlToParserService() throws Exception {
        RagProperties properties = new RagProperties();
        ParserServiceClient client = mock(ParserServiceClient.class);
        MinioclientUtil minio = mock(MinioclientUtil.class);
        when(minio.getPresignedObjectUrl("file-1",300)).thenReturn("http://minio/object");
        OcrStructuredParser parser = new OcrStructuredParser(properties,client,minio);

        parser.parse(spaceFile(),file());

        verify(client).parseOcr(org.mockito.ArgumentMatchers.argThat(request ->
                "http://minio/object".equals(request.objectUrl())
        ));
    }

    private SpaceFile spaceFile() {
        SpaceFile spaceFile = new SpaceFile();
        spaceFile.setFileUuid("file-1");
        spaceFile.setFileName("scan.pdf");
        return spaceFile;
    }

    private File file() {
        File file = new File();
        file.setFileUuid("file-1");
        file.setHash("hash-1");
        file.setType("pdf");
        return file;
    }
}
