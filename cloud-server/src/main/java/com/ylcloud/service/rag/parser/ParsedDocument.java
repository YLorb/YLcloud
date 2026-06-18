package com.ylcloud.service.rag.parser;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedDocument {
    private boolean success;
    private boolean fallback;
    private String fileUuid;
    private String fileHash;
    private String parser;
    private String parserVersion;
    private String fullText;
    private List<DocumentBlock> blocks = new ArrayList<>();
    private String errorMessage;

    public static ParsedDocument success(String fileUuid, String fileHash, String parser, String parserVersion,
                                         String fullText, List<DocumentBlock> blocks) {
        return new ParsedDocument(true,false,fileUuid,fileHash,parser,parserVersion,fullText,blocks,null);
    }

    public static ParsedDocument fallback(String fileUuid, String fileHash, String parser, String parserVersion,
                                          String fullText, String errorMessage) {
        List<DocumentBlock> blocks = new ArrayList<>();
        blocks.add(new DocumentBlock(0,"metadata_fallback",fullText, null, null, List.of(), null, 1.0, parser));
        return new ParsedDocument(true,true,fileUuid,fileHash,parser,parserVersion,fullText,blocks,errorMessage);
    }

    public static ParsedDocument failed(String fileUuid, String fileHash, String parser, String parserVersion,
                                        String errorMessage) {
        return new ParsedDocument(false,false,fileUuid,fileHash,parser,parserVersion,null,List.of(),errorMessage);
    }
}
