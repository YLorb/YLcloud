package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.utils.MinioclientUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class VlmPageParser {
    private final RagProperties ragProperties;
    private final ParserServiceClient parserServiceClient;
    private final MinioclientUtil minioclientUtil;

    public VlmPageParser(RagProperties ragProperties, ParserServiceClient parserServiceClient) {
        this(ragProperties,parserServiceClient,null);
    }

    @Autowired
    public VlmPageParser(RagProperties ragProperties, ParserServiceClient parserServiceClient, MinioclientUtil minioclientUtil) {
        this.ragProperties = ragProperties;
        this.parserServiceClient = parserServiceClient;
        this.minioclientUtil = minioclientUtil;
    }

    public ParsedDocument parseFirstPage(SpaceFile spaceFile, File file) {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        String parserVersion = extraction == null || extraction.getParserVersion() == null ? "structured-v2" : extraction.getParserVersion();
        if(extraction == null || !Boolean.TRUE.equals(extraction.getVlmEnabled())) {
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"vlm-page",parserVersion,"VLM parser is disabled");
        }
        if(minioclientUtil == null) {
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"vlm-page",parserVersion,
                    "VLM object URL provider is unavailable");
        }
        String objectUrl;
        try {
            int ttlSeconds = extraction.getParserObjectUrlTtlSeconds() == null ? 300 : extraction.getParserObjectUrlTtlSeconds();
            objectUrl = minioclientUtil.getPresignedObjectUrl(fileUuid(spaceFile,file),ttlSeconds);
        } catch (Exception ex) {
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"vlm-page",parserVersion,
                    "Failed to create VLM object URL: " + ex.getMessage());
        }
        return parserServiceClient.parseVlmPage(new ParserServiceClient.VlmParseRequest(
                fileUuid(spaceFile,file),
                fileHash(file),
                spaceFile == null ? null : spaceFile.getFileName(),
                file == null ? null : file.getType(),
                objectUrl,
                1,
                extraction.getMaxVlmPages(),
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
