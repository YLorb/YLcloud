package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.utils.MinioclientUtil;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Preserves PPTX slide boundaries instead of flattening the presentation into one Tika string.
 */
@Service
public class PptxStructuredParser {
    private final MinioclientUtil minioclientUtil;
    private final RagProperties ragProperties;

    public PptxStructuredParser(MinioclientUtil minioclientUtil, RagProperties ragProperties) {
        this.minioclientUtil = minioclientUtil;
        this.ragProperties = ragProperties;
    }

    public ParsedDocument parse(SpaceFile spaceFile, File file) {
        if(file == null || file.getFileUuid() == null || file.getFileUuid().isBlank()) {
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"pptx-structured",parserVersion(),"PPTX 文件 UUID 不存在");
        }
        try(InputStream input = minioclientUtil.getObjectStream(file.getFileUuid());
            XMLSlideShow slideshow = new XMLSlideShow(input)) {
            List<DocumentBlock> blocks = new ArrayList<>();
            int[] order = {0};
            int pageNo = 0;
            for(XSLFSlide slide : slideshow.getSlides()) {
                pageNo++;
                List<String> headingPath = new ArrayList<>();
                appendShapes(blocks,slide.getShapes(),pageNo,headingPath,order,"pptx-slide");
                if(slide.getNotes() != null) {
                    appendShapes(blocks,slide.getNotes().getShapes(),pageNo,headingPath,order,"pptx-notes");
                }
            }
            String fullText = blocks.stream().map(DocumentBlock::getText)
                    .filter(text -> text != null && !text.isBlank()).reduce((left,right) -> left + "\n\n" + right).orElse("");
            if(fullText.isBlank()) {
                return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"pptx-structured",parserVersion(),"PPTX 未提取到可索引正文");
            }
            return ParsedDocument.success(fileUuid(spaceFile,file),fileHash(file),"pptx-structured",parserVersion(),fullText,blocks);
        } catch (Exception ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"pptx-structured",parserVersion(),"PPTX 解析失败：" + message);
        }
    }

    private void appendShapes(List<DocumentBlock> blocks, List<XSLFShape> shapes, int pageNo,
                              List<String> headingPath, int[] order, String source) {
        for(XSLFShape shape : shapes) {
            if(shape instanceof XSLFTable table) {
                String markdown = tableMarkdown(table);
                if(!markdown.isBlank()) blocks.add(new DocumentBlock(order[0]++,"table",markdown,pageNo,null,new ArrayList<>(headingPath),null,1.0,source + "-table"));
                continue;
            }
            if(!(shape instanceof XSLFTextShape textShape)) continue;
            String text = normalize(textShape.getText());
            if(text.isBlank() || isNotesBoilerplate(text,source)) continue;
            boolean title = textShape.getTextType() == Placeholder.TITLE || textShape.getTextType() == Placeholder.CENTERED_TITLE;
            if(title) {
                headingPath.clear();
                headingPath.add(text);
            }
            blocks.add(new DocumentBlock(order[0]++,title ? "heading" : source.endsWith("notes") ? "notes" : "paragraph",
                    text,pageNo,title ? 1 : null,new ArrayList<>(headingPath),null,1.0,source));
        }
    }

    private String tableMarkdown(XSLFTable table) {
        List<String> rows = new ArrayList<>();
        int columns = 0;
        for(XSLFTableRow row : table.getRows()) columns = Math.max(columns,row.getCells().size());
        for(XSLFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for(XSLFTableCell cell : row.getCells()) cells.add(escapeCell(cell.getText()));
            while(cells.size() < columns) cells.add("");
            rows.add("| " + String.join(" | ",cells) + " |");
            if(rows.size() == 1) rows.add("| " + "--- | ".repeat(Math.max(1,columns)).trim());
        }
        return String.join("\n",rows);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\u0000',' ').replaceAll("[\\t\\x0B\\f\\r]+"," ")
                .replaceAll("\\n{3,}","\n\n").trim();
    }

    private boolean isNotesBoilerplate(String text, String source) {
        return source.endsWith("notes") && (text.matches("(?i)slide \\d+") || text.matches("\\d+"));
    }

    private String escapeCell(String value) {
        return normalize(value).replace("\n"," ").replace("|","\\|");
    }

    private String parserVersion() {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        return extraction == null || extraction.getParserVersion() == null ? "structured-v3" : extraction.getParserVersion();
    }

    private String fileUuid(SpaceFile spaceFile, File file) {
        return spaceFile != null && spaceFile.getFileUuid() != null ? spaceFile.getFileUuid() : file == null ? null : file.getFileUuid();
    }

    private String fileHash(File file) {
        return file == null ? null : file.getHash();
    }
}
