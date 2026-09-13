package com.ylcloud.service.rag.parser;

import com.ylcloud.config.RagProperties;
import org.springframework.stereotype.Component;

@Component
public class DocumentParseQualityAssessor {
    private final RagProperties ragProperties;

    public DocumentParseQualityAssessor(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public boolean shouldEnhanceWithVlm(ParsedDocument document) {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        if(extraction == null || !Boolean.TRUE.equals(extraction.getVlmEnabled())) {
            return false;
        }
        if(document == null || !document.isSuccess()) {
            return true;
        }
        String text = document.getFullText() == null ? "" : document.getFullText();
        int minTextChars = extraction.getMinTextCharsBeforeOcr() == null ? 300 : extraction.getMinTextCharsBeforeOcr();
        return text.length() < minTextChars || validTextRatio(text) < 0.45;
    }

    public boolean shouldEnhanceWithOcr(ParsedDocument document) {
        RagProperties.Extraction extraction = ragProperties.getExtraction();
        if(extraction == null || !Boolean.TRUE.equals(extraction.getOcrEnabled())) {
            return false;
        }
        return isLowQuality(document);
    }

    public boolean isLowQuality(ParsedDocument document) {
        if(document == null || !document.isSuccess()) {
            return true;
        }
        String text = document.getFullText() == null ? "" : document.getFullText();
        int minTextChars = ragProperties.getExtraction() == null || ragProperties.getExtraction().getMinTextCharsBeforeOcr() == null
                ? 300 : ragProperties.getExtraction().getMinTextCharsBeforeOcr();
        return text.length() < minTextChars || validTextRatio(text) < 0.35;
    }

    private double validTextRatio(String text) {
        if(text == null || text.isBlank()) {
            return 0.0;
        }
        int valid = 0;
        for(int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if(Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                valid++;
            }
        }
        return valid * 1.0 / text.length();
    }
}
