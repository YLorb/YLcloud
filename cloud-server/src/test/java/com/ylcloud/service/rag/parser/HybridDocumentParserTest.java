package com.ylcloud.service.rag.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.File;
import com.ylcloud.entity.FileRagParseResult;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.FileRagParseResultMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class HybridDocumentParserTest {
    @Test
    void rejectsPlainTextCacheForDocxAndUsesStructuredParser() {
        RagProperties properties = new RagProperties();
        properties.getExtraction().setLayoutEnabled(false);
        DocxStructuredParser docxParser = mock(DocxStructuredParser.class);
        TikaStructuredParser tikaParser = mock(TikaStructuredParser.class);
        LayoutStructuredParser layoutParser = mock(LayoutStructuredParser.class);
        OcrStructuredParser ocrParser = mock(OcrStructuredParser.class);
        VlmPageParser vlmParser = mock(VlmPageParser.class);
        DocumentParseQualityAssessor quality = mock(DocumentParseQualityAssessor.class);
        FileRagParseResultMapper cacheMapper = mock(FileRagParseResultMapper.class);

        SpaceFile spaceFile = new SpaceFile();
        spaceFile.setFileUuid("docx-1");
        spaceFile.setFileName("unique.docx");
        File file = new File();
        file.setFileUuid("docx-1");
        file.setHash("hash-1");
        file.setType("docx");

        FileRagParseResult stale = new FileRagParseResult();
        stale.setId(5L);
        stale.setFileUuid("docx-1");
        stale.setFileHash("hash-1");
        stale.setParserVersion("structured-v2");
        stale.setParser("plain-text");
        stale.setParseStatus(SpaceConstant.RAG_TASK_SUCCESS);
        stale.setFullText("PK zip bytes");
        when(cacheMapper.getByFileAndVersion("docx-1","hash-1","structured-v2")).thenReturn(stale);

        ParsedDocument structured = ParsedDocument.success("docx-1","hash-1","docx-structured","structured-v2",
                "DOCX_UNIQUE_PHRASE",List.of(new DocumentBlock()));
        when(docxParser.parse(spaceFile,file)).thenReturn(structured);
        when(quality.isLowQuality(structured)).thenReturn(false);
        when(quality.shouldEnhanceWithOcr(any())).thenReturn(true);
        when(quality.shouldEnhanceWithVlm(any())).thenReturn(true);

        HybridDocumentParser parser = new HybridDocumentParser(properties,docxParser,tikaParser,layoutParser,
                ocrParser,vlmParser,quality,cacheMapper,new ObjectMapper());
        ParsedDocument result = parser.parse(spaceFile,file);

        assertEquals("docx-structured",result.getParser());
        assertEquals("DOCX_UNIQUE_PHRASE",result.getFullText());
        verify(cacheMapper).disableById(5L);
        verify(docxParser).parse(spaceFile,file);
        verify(ocrParser,never()).parse(any(),any());
        verify(vlmParser,never()).parseFirstPage(any(),any());
        verify(cacheMapper).insert(any(FileRagParseResult.class));
    }
}
