package com.ylcloud.service.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedDocumentText {
    private boolean success;
    private boolean fallback;
    private String text;
    private String parser;
    private String errorMessage;

    public static ExtractedDocumentText success(String text, String parser) {
        return new ExtractedDocumentText(true,false,text,parser,null);
    }

    public static ExtractedDocumentText fallback(String message) {
        return new ExtractedDocumentText(false,true,null,"metadata",message);
    }
}
