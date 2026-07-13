package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.service.rag.DocumentTextExtractor;
import com.ylcloud.service.rag.ExtractedDocumentText;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TikaStructuredParser {
    private final DocumentTextExtractor documentTextExtractor;
    private final RagProperties ragProperties;

    public TikaStructuredParser(DocumentTextExtractor documentTextExtractor, RagProperties ragProperties) {
        this.documentTextExtractor = documentTextExtractor;
        this.ragProperties = ragProperties;
    }

    public ParsedDocument parse(SpaceFile spaceFile, File file) {
        String parserVersion = parserVersion();
        ExtractedDocumentText extracted = documentTextExtractor.extract(spaceFile,file);
        if(extracted == null || !extracted.isSuccess() || extracted.getText() == null || extracted.getText().isBlank()) {
            String message = extracted == null ? "Document parse failed" : extracted.getErrorMessage();
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"tika-structured",parserVersion,message);
        }
        String text = normalize(extracted.getText());
        List<DocumentBlock> blocks = toBlocks(text,extracted.getParser());
        return ParsedDocument.success(fileUuid(spaceFile,file),fileHash(file),"tika-structured",parserVersion,text,blocks);
    }

    public ParsedDocument fallback(SpaceFile spaceFile, File file, String message) {
        return ParsedDocument.fallback(fileUuid(spaceFile,file),fileHash(file),"metadata",parserVersion(),
                buildMetadataText(spaceFile,file),message);
    }

    private List<DocumentBlock> toBlocks(String text, String source) {
        List<DocumentBlock> blocks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        String[] parts = text.split("\\n\\s*\\n");
        int index = 0;
        for(String part : parts) {
            String value = part.trim();
            if(value.isBlank()) {
                continue;
            }
            String type = detectType(value);
            Integer level = headingLevel(value,type);
            if("heading".equals(type)) {
                while(headingPath.size() >= level) {
                    headingPath.remove(headingPath.size() - 1);
                }
                headingPath.add(cleanHeading(value));
            }
            blocks.add(new DocumentBlock(index++,type,value,null,level,new ArrayList<>(headingPath),null,1.0,source));
        }
        if(blocks.isEmpty() && !text.isBlank()) {
            blocks.add(new DocumentBlock(0,"paragraph",text,null,null,List.of(),null,1.0,source));
        }
        return blocks;
    }

    private String detectType(String text) {
        String trimmed = text.trim();
        if(trimmed.startsWith("#")) {
            return "heading";
        }
        if(trimmed.contains("|") && trimmed.contains("\n")) {
            return "table";
        }
        if(trimmed.matches("(?s)^\\s*([-*+]\\s+|\\d+[.)]\\s+).+")) {
            return "list";
        }
        if(trimmed.length() <= 80 && trimmed.matches(".*(章|节|、|\\d+(\\.\\d+)*\\s+).*$")) {
            return "heading";
        }
        return "paragraph";
    }

    private Integer headingLevel(String text, String type) {
        if(!"heading".equals(type)) {
            return null;
        }
        int count = 0;
        while(count < text.length() && text.charAt(count) == '#') {
            count++;
        }
        return count == 0 ? 1 : Math.min(count,6);
    }

    private String cleanHeading(String text) {
        return text.replaceFirst("^#+\\s*","").trim();
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace('\u0000',' ')
                .replaceAll("[\\t\\x0B\\f\\r]+"," ")
                .replaceAll("\\n{3,}","\n\n")
                .trim();
    }

    private String buildMetadataText(SpaceFile spaceFile, File file) {
        StringBuilder builder = new StringBuilder();
        builder.append("文件名称：").append(spaceFile == null ? "" : spaceFile.getFileName()).append('\n');
        builder.append("空间路径：").append(spaceFile == null ? "" : spaceFile.getPath()).append('\n');
        builder.append("文件 UUID：").append(spaceFile == null ? "" : spaceFile.getFileUuid()).append('\n');
        if(file != null) {
            builder.append("文件类型：").append(file.getType()).append('\n');
            builder.append("文件大小：").append(file.getSize()).append('\n');
            builder.append("文件哈希：").append(file.getHash()).append('\n');
        }
        builder.append("说明：当前文件正文解析失败，已保存文件元数据用于检索。");
        return builder.toString();
    }

    private String parserVersion() {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        return extraction == null || extraction.getParserVersion() == null ? "structured-v2" : extraction.getParserVersion();
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
