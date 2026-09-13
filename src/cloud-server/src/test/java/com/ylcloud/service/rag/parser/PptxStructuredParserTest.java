package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.utils.MinioclientUtil;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PptxStructuredParserTest {
    @Test
    void extractsTextWithRealSlideNumbers() throws Exception {
        byte[] content;
        try(XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addSlide(show,"Ubuntu 安装准备");
            addSlide(show,"选择磁盘分区");
            show.write(output);
            content = output.toByteArray();
        }
        MinioclientUtil minio = mock(MinioclientUtil.class);
        when(minio.getObjectStream("pptx-1")).thenReturn(new ByteArrayInputStream(content));
        PptxStructuredParser parser = new PptxStructuredParser(minio,new RagProperties());
        SpaceFile spaceFile = new SpaceFile();
        spaceFile.setFileUuid("pptx-1");
        spaceFile.setFileName("ubuntu.pptx");
        File file = new File();
        file.setFileUuid("pptx-1");
        file.setHash("hash-1");

        ParsedDocument parsed = parser.parse(spaceFile,file);

        assertTrue(parsed.isSuccess());
        assertFalse(parsed.isFallback());
        assertEquals("pptx-structured",parsed.getParser());
        assertTrue(parsed.getFullText().contains("Ubuntu 安装准备"));
        assertEquals(1,parsed.getBlocks().get(0).getPageNo());
        assertEquals(2,parsed.getBlocks().get(1).getPageNo());
    }

    private void addSlide(XMLSlideShow show, String text) {
        XSLFSlide slide = show.createSlide();
        XSLFTextBox box = slide.createTextBox();
        box.setText(text);
    }
}
