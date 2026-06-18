package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.utils.MinioclientUtil;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocxStructuredParserTest {

    @Test
    void extractsParagraphTableAndEmbeddedImageBlocks() throws Exception {
        MinioclientUtil minioclientUtil = mock(MinioclientUtil.class);
        when(minioclientUtil.getObjectStream("file-1")).thenReturn(new ByteArrayInputStream(docxBytes()));
        DocxStructuredParser parser = new DocxStructuredParser(minioclientUtil,new RagProperties());

        ParsedDocument result = parser.parse(spaceFile(),file());

        assertTrue(result.isSuccess());
        Set<String> types = result.getBlocks().stream().map(DocumentBlock::getType).collect(Collectors.toSet());
        assertTrue(types.contains("paragraph"));
        assertTrue(types.contains("table"));
        assertTrue(types.contains("figure"));
        assertTrue(result.getFullText().contains("| Field | Value |"));
    }

    private byte[] docxBytes() throws Exception {
        try(XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText("Project overview");

            XWPFTable table = document.createTable(2,2);
            table.getRow(0).getCell(0).setText("Field");
            table.getRow(0).getCell(1).setText("Value");
            table.getRow(1).getCell(0).setText("Status");
            table.getRow(1).getCell(1).setText("Ready");

            XWPFParagraph imageParagraph = document.createParagraph();
            XWPFRun run = imageParagraph.createRun();
            run.addPicture(new ByteArrayInputStream(tinyPng()), Document.PICTURE_TYPE_PNG,"tiny.png",1,1);

            document.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private byte[] tinyPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");
    }

    private SpaceFile spaceFile() {
        SpaceFile spaceFile = new SpaceFile();
        spaceFile.setFileUuid("file-1");
        spaceFile.setFileName("sample.docx");
        return spaceFile;
    }

    private File file() {
        File file = new File();
        file.setFileUuid("file-1");
        file.setHash("hash-1");
        file.setType("docx");
        return file;
    }
}
