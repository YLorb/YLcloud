package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import org.springframework.stereotype.Component;

@Component
public class VlmPageParser {
    private final RagProperties ragProperties;
    private final ParserServiceClient parserServiceClient;

    public VlmPageParser(RagProperties ragProperties, ParserServiceClient parserServiceClient) {
        this.ragProperties = ragProperties;
        this.parserServiceClient = parserServiceClient;
    }

    public ParsedDocument parseFirstPage(SpaceFile spaceFile, File file) {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        String parserVersion = extraction == null || extraction.getParserVersion() == null ? "structured-v1" : extraction.getParserVersion();
        if(extraction == null || !Boolean.TRUE.equals(extraction.getVlmEnabled())) {
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"vlm-page",parserVersion,"VLM parser is disabled");
        }
        return parserServiceClient.parseVlmPage(new ParserServiceClient.VlmParseRequest(
                fileUuid(spaceFile,file),
                fileHash(file),
                spaceFile == null ? null : spaceFile.getFileName(),
                file == null ? null : file.getType(),
                1,
                parserVersion
        ));
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
