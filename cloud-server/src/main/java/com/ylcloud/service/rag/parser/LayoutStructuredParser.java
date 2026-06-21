package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import com.ylcloud.entity.File;
import com.ylcloud.entity.SpaceFile;
import com.ylcloud.utils.MinioclientUtil;
import org.springframework.stereotype.Component;

@Component
public class LayoutStructuredParser {
    private final RagProperties ragProperties;
    private final ParserServiceClient parserServiceClient;
    private final MinioclientUtil minioclientUtil;

    public LayoutStructuredParser(RagProperties ragProperties,
                                  ParserServiceClient parserServiceClient,
                                  MinioclientUtil minioclientUtil) {
        this.ragProperties = ragProperties;
        this.parserServiceClient = parserServiceClient;
        this.minioclientUtil = minioclientUtil;
    }

    public ParsedDocument parse(SpaceFile spaceFile, File file) {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        String parserVersion = extraction == null || extraction.getParserVersion() == null ? "structured-v1" : extraction.getParserVersion();
        if(extraction == null || !Boolean.TRUE.equals(extraction.getLayoutEnabled())) {
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"pdf-layout",parserVersion,"Layout parser is disabled");
        }
        String objectUrl;
        try {
            int ttlSeconds = extraction.getParserObjectUrlTtlSeconds() == null ? 300 : extraction.getParserObjectUrlTtlSeconds();
            objectUrl = minioclientUtil.getPresignedObjectUrl(fileUuid(spaceFile,file),ttlSeconds);
        } catch (Exception ex) {
            return ParsedDocument.failed(fileUuid(spaceFile,file),fileHash(file),"pdf-layout",parserVersion,
                    "Failed to create layout parser object URL: " + ex.getMessage());
        }
        return parserServiceClient.parseLayout(new ParserServiceClient.LayoutParseRequest(
                fileUuid(spaceFile,file),
                fileHash(file),
                spaceFile == null ? null : spaceFile.getFileName(),
                file == null ? null : file.getType(),
                objectUrl,
                extraction.getMaxOcrPages(),
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
