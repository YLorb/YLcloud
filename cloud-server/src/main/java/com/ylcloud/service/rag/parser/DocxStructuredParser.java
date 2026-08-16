package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.utils.MinioclientUtil;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocxStructuredParser {
    private static final Pattern TEXT_NODE = Pattern.compile("<[^>]*:t[^>]*>(.*?)</[^>]*:t>");

    private final MinioclientUtil minioclientUtil;
    private final RagProperties ragProperties;

    public DocxStructuredParser(MinioclientUtil minioclientUtil, RagProperties ragProperties) {
        this.minioclientUtil = minioclientUtil;
        this.ragProperties = ragProperties;
    }

    public ParsedDocument parse(SpaceFile spaceFile, File file) {
        String parserVersion = parserVersion();
        if(file == null || file.getFileUuid() == null || file.getFileUuid().isBlank()) {
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"docx-structured",parserVersion,"Missing file UUID");
        }
        try(InputStream inputStream = minioclientUtil.getObjectStream(file.getFileUuid());
            XWPFDocument document = new XWPFDocument(inputStream)) {
            List<DocumentBlock> blocks = new ArrayList<>();
            List<String> headingPath = new ArrayList<>();
            int[] index = new int[] {0};
            for(IBodyElement element : document.getBodyElements()) {
                if(element instanceof XWPFParagraph paragraph) {
                    addParagraphBlocks(blocks,paragraph,headingPath,index);
                } else if(element instanceof XWPFTable table) {
                    addTableBlock(blocks,table,headingPath,index);
                }
            }
            addHeaderFooterBlocks(blocks,document,index);
            String fullText = fullText(blocks);
            if(fullText.isBlank()) {
                return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"docx-structured",parserVersion,"DOCX content is empty");
            }
            return ParsedDocument.success(fileUuid(spaceFile,file),fileHash(file),"docx-structured",parserVersion,fullText,blocks);
        } catch (Exception ex) {
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"docx-structured",parserVersion,ex.getMessage());
        }
    }

    private void addParagraphBlocks(List<DocumentBlock> blocks, XWPFParagraph paragraph, List<String> headingPath, int[] index) {
        String text = paragraph.getText() == null ? "" : paragraph.getText().trim();
        String xmlText = extraXmlText(paragraph,text);
        String type = paragraphType(paragraph,text);
        Integer level = headingLevel(paragraph,type);
        if(!text.isBlank()) {
            if("heading".equals(type)) {
                while(headingPath.size() >= level) {
                    headingPath.remove(headingPath.size() - 1);
                }
                headingPath.add(text);
            }
            blocks.add(new DocumentBlock(index[0]++,type,text,null,level,new ArrayList<>(headingPath),null,1.0,"docx-paragraph"));
        }
        if(!xmlText.isBlank()) {
            blocks.add(new DocumentBlock(index[0]++,"text_box",xmlText,null,null,new ArrayList<>(headingPath),null,0.8,"docx-shape-text"));
        }
        for(XWPFRun run : paragraph.getRuns()) {
            for(XWPFPicture picture : run.getEmbeddedPictures()) {
                XWPFPictureData data = picture.getPictureData();
                String fileName = data == null ? "embedded-image" : data.getFileName();
                String typeInfo = data == null ? "" : data.getPackagePart().getContentType();
                String description = "Embedded image: " + fileName;
                String metadata = "{\"imageId\":\"docx-image-" + index[0] + "\",\"fileName\":\"" + escape(fileName) +
                        "\",\"contentType\":\"" + escape(typeInfo) + "\"}";
                blocks.add(new DocumentBlock(index[0]++,"figure",description,null,null,new ArrayList<>(headingPath),metadata,1.0,"docx-image"));
            }
        }
    }

    private void addTableBlock(List<DocumentBlock> blocks, XWPFTable table, List<String> headingPath, int[] index) {
        List<String> rows = new ArrayList<>();
        int maxCols = 0;
        for(XWPFTableRow row : table.getRows()) {
            maxCols = Math.max(maxCols,row.getTableCells().size());
        }
        for(XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for(XWPFTableCell cell : row.getTableCells()) {
                cells.add(escapeMarkdownCell(cell.getText()));
            }
            while(cells.size() < maxCols) {
                cells.add("");
            }
            rows.add("| " + String.join(" | ",cells) + " |");
            if(rows.size() == 1) {
                rows.add("| " + "--- | ".repeat(Math.max(1,maxCols)).trim());
            }
        }
        String markdown = String.join("\n",rows);
        String metadata = "{\"tableId\":\"docx-table-" + index[0] + "\",\"rowCount\":" + table.getRows().size() +
                ",\"colCount\":" + maxCols + "}";
        blocks.add(new DocumentBlock(index[0]++,"table",markdown,null,null,new ArrayList<>(headingPath),metadata,1.0,"docx-table"));
    }

    private void addHeaderFooterBlocks(List<DocumentBlock> blocks, XWPFDocument document, int[] index) {
        for(XWPFHeader header : document.getHeaderList()) {
            String text = header.getText() == null ? "" : header.getText().trim();
            if(!text.isBlank()) {
                blocks.add(new DocumentBlock(index[0]++,"header",text,null,null,List.of(),null,1.0,"docx-header"));
            }
        }
        for(XWPFFooter footer : document.getFooterList()) {
            String text = footer.getText() == null ? "" : footer.getText().trim();
            if(!text.isBlank()) {
                blocks.add(new DocumentBlock(index[0]++,"footer",text,null,null,List.of(),null,1.0,"docx-footer"));
            }
        }
    }

    private String paragraphType(XWPFParagraph paragraph, String text) {
        if(text == null || text.isBlank()) {
            return "paragraph";
        }
        String style = paragraph.getStyle() == null ? "" : paragraph.getStyle().toLowerCase();
        if(style.contains("heading") || style.matches(".*\\b[1-6]\\b.*")) {
            return "heading";
        }
        if(paragraph.getNumID() != null) {
            return "list";
        }
        return "paragraph";
    }

    private Integer headingLevel(XWPFParagraph paragraph, String type) {
        if(!"heading".equals(type)) {
            return null;
        }
        String style = paragraph.getStyle() == null ? "" : paragraph.getStyle();
        Matcher matcher = Pattern.compile("([1-6])").matcher(style);
        if(matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 1;
    }

    private String extraXmlText(XWPFParagraph paragraph, String visibleText) {
        String xml = paragraph.getCTP().xmlText();
        Matcher matcher = TEXT_NODE.matcher(xml);
        List<String> values = new ArrayList<>();
        while(matcher.find()) {
            String value = unescapeXml(matcher.group(1)).trim();
            if(!value.isBlank()) {
                values.add(value);
            }
        }
        String merged = String.join("",values).trim();
        if(merged.isBlank() || (visibleText != null && visibleText.contains(merged))) {
            return "";
        }
        return merged;
    }

    private String fullText(List<DocumentBlock> blocks) {
        StringBuilder builder = new StringBuilder();
        for(DocumentBlock block : blocks) {
            if(block.getText() == null || block.getText().isBlank()) {
                continue;
            }
            if(builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(block.getText());
        }
        return builder.toString();
    }

    private String escapeMarkdownCell(String value) {
        return value == null ? "" : value.replace("\n"," ").replace("|","\\|").trim();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\","\\\\").replace("\"","\\\"");
    }

    private String unescapeXml(String value) {
        return value.replace("&lt;","<").replace("&gt;",">").replace("&amp;","&")
                .replace("&quot;","\"").replace("&apos;","'");
    }

    private String parserVersion() {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        return extraction == null || extraction.getParserVersion() == null ? "structured-v3" : extraction.getParserVersion();
    }

    private String fileUuid(SpaceFile spaceFile, File file) {
        if(spaceFile != null && spaceFile.getFileUuid() != null) {
            return spaceFile.getFileUuid();
        }
        return file == null ? null : file.getFileUuid();
    }

    private String fileHash(File file) {
        return file == null ? null : file.getHash();
    }
}
