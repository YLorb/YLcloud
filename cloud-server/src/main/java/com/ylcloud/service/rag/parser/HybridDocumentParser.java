package com.ylcloud.service.rag.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.constant.StatusConstant;
import com.ylcloud.entity.File;
import com.ylcloud.entity.FileRagParseResult;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.mapper.FileRagParseResultMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HybridDocumentParser implements DocumentParser {
    private final RagProperties ragProperties;
    private final DocxStructuredParser docxStructuredParser;
    private final TikaStructuredParser tikaStructuredParser;
    private final LayoutStructuredParser layoutStructuredParser;
    private final OcrStructuredParser ocrStructuredParser;
    private final VlmPageParser vlmPageParser;
    private final DocumentParseQualityAssessor qualityAssessor;
    private final FileRagParseResultMapper parseResultMapper;
    private final ObjectMapper objectMapper;

    public HybridDocumentParser(RagProperties ragProperties,
                                DocxStructuredParser docxStructuredParser,
                                TikaStructuredParser tikaStructuredParser,
                                LayoutStructuredParser layoutStructuredParser,
                                OcrStructuredParser ocrStructuredParser,
                                VlmPageParser vlmPageParser,
                                DocumentParseQualityAssessor qualityAssessor,
                                FileRagParseResultMapper parseResultMapper,
                                ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.docxStructuredParser = docxStructuredParser;
        this.tikaStructuredParser = tikaStructuredParser;
        this.layoutStructuredParser = layoutStructuredParser;
        this.ocrStructuredParser = ocrStructuredParser;
        this.vlmPageParser = vlmPageParser;
        this.qualityAssessor = qualityAssessor;
        this.parseResultMapper = parseResultMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ParsedDocument parse(SpaceFile spaceFile, File file) {
        String parserVersion = parserVersion();
        FileRagParseResult cached = parseResultMapper.getByFileAndVersion(fileUuid(spaceFile,file),fileHash(file),parserVersion);
        ParsedDocument cachedDocument = fromCache(cached);
        if(cachedDocument != null) {
            return cachedDocument;
        }

        ParsedDocument parsed = shouldUseLayout(spaceFile,file)
                ? layoutStructuredParser.parse(spaceFile,file)
                : isDocx(spaceFile,file) ? docxStructuredParser.parse(spaceFile,file) : tikaStructuredParser.parse(spaceFile,file);
        if((!parsed.isSuccess() || qualityAssessor.isLowQuality(parsed)) && shouldUseLayout(spaceFile,file)) {
            ParsedDocument tikaParsed = isDocx(spaceFile,file) ? docxStructuredParser.parse(spaceFile,file) : tikaStructuredParser.parse(spaceFile,file);
            if(tikaParsed.isSuccess() && !qualityAssessor.isLowQuality(tikaParsed)) {
                parsed = tikaParsed;
            }
        }
        if(!parsed.isSuccess() && isDocx(spaceFile,file)) {
            parsed = tikaStructuredParser.parse(spaceFile,file);
        }
        if(qualityAssessor.shouldEnhanceWithOcr(parsed)) {
            ParsedDocument ocrParsed = ocrStructuredParser.parse(spaceFile,file);
            if(ocrParsed.isSuccess() && !qualityAssessor.isLowQuality(ocrParsed)) {
                parsed = ocrParsed;
            }
        }
        if(qualityAssessor.shouldEnhanceWithVlm(parsed)) {
            ParsedDocument vlmParsed = vlmPageParser.parseFirstPage(spaceFile,file);
            if(vlmParsed.isSuccess()) {
                parsed = vlmParsed;
            }
        }
        if(!parsed.isSuccess()) {
            if(fallbackToMetadata()) {
                parsed = tikaStructuredParser.fallback(spaceFile,file,parsed.getErrorMessage());
            } else {
                saveResult(parsed);
                return parsed;
            }
        }
        saveResult(parsed);
        return parsed;
    }

    private ParsedDocument fromCache(FileRagParseResult cached) {
        if(cached == null || !SpaceConstant.RAG_TASK_SUCCESS.equals(cached.getParseStatus())) {
            return null;
        }
        if("metadata".equals(cached.getParser())) {
            parseResultMapper.disableMetadataCache(cached.getFileUuid(),cached.getFileHash(),cached.getParserVersion());
            return null;
        }
        try {
            List<DocumentBlock> blocks = cached.getBlocksJson() == null || cached.getBlocksJson().isBlank()
                    ? List.of()
                    : objectMapper.readValue(cached.getBlocksJson(),new TypeReference<List<DocumentBlock>>() {});
            return new ParsedDocument(true,"metadata".equals(cached.getParser()),cached.getFileUuid(),cached.getFileHash(),
                    cached.getParser(),cached.getParserVersion(),cached.getFullText(),blocks,cached.getErrorMessage());
        } catch (Exception ex) {
            return null;
        }
    }

    private void saveResult(ParsedDocument document) {
        try {
            LocalDateTime now = LocalDateTime.now();
            FileRagParseResult result = new FileRagParseResult();
            result.setFileUuid(document.getFileUuid());
            result.setFileHash(document.getFileHash());
            result.setParser(document.getParser());
            result.setParserVersion(document.getParserVersion());
            result.setParseStatus(document.isSuccess() ? SpaceConstant.RAG_TASK_SUCCESS : SpaceConstant.RAG_TASK_FAILED);
            result.setFullText(document.getFullText());
            result.setBlocksJson(objectMapper.writeValueAsString(document.getBlocks()));
            result.setErrorMessage(document.getErrorMessage());
            result.setStatus(StatusConstant.ENABLE);
            result.setCreatetime(now);
            result.setUpdatetime(now);
            parseResultMapper.insert(result);
        } catch (Exception ignored) {
            // Parse caching must not break the indexing task.
        }
    }

    private boolean fallbackToMetadata() {
        return ragProperties.getExtraction() == null || !Boolean.FALSE.equals(ragProperties.getExtraction().getFallbackToMetadata());
    }

    private boolean shouldUseLayout(SpaceFile spaceFile, File file) {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        if(extraction == null || !Boolean.TRUE.equals(extraction.getLayoutEnabled())) {
            return false;
        }
        String name = spaceFile != null && spaceFile.getFileName() != null ? spaceFile.getFileName() : file == null ? null : file.getName();
        String type = file == null || file.getType() == null ? "" : file.getType().toLowerCase();
        String lowerName = name == null ? "" : name.toLowerCase();
        return lowerName.endsWith(".pdf") || lowerName.endsWith(".png") || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") || type.contains("pdf") || type.startsWith("image");
    }

    private String parserVersion() {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        return extraction == null || extraction.getParserVersion() == null ? "structured-v1" : extraction.getParserVersion();
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

    private boolean isDocx(SpaceFile spaceFile, File file) {
        String name = spaceFile != null && spaceFile.getFileName() != null ? spaceFile.getFileName() : file == null ? null : file.getName();
        if(name != null && name.toLowerCase().endsWith(".docx")) {
            return true;
        }
        return file != null && file.getType() != null && file.getType().toLowerCase().contains("docx");
    }
}
