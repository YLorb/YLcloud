package com.ylcloud.service;

import org.springframework.stereotype.Component;

/** Deterministic conservative token estimator for mixed Chinese and Latin text. */
@Component
public class ConversationTokenEstimator {
    public int estimate(String text) {
        if(text == null || text.isBlank()) return 0;
        int tokens = 0;
        int latinRun = 0;
        for(int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if(isCjk(codePoint)) {
                tokens += flushLatin(latinRun) + 1;
                latinRun = 0;
            } else if(Character.isLetterOrDigit(codePoint)) {
                latinRun++;
            } else {
                tokens += flushLatin(latinRun);
                latinRun = 0;
                if(!Character.isWhitespace(codePoint)) tokens++;
            }
        }
        return Math.max(1,tokens + flushLatin(latinRun));
    }

    private int flushLatin(int length) { return length == 0 ? 0 : (int) Math.ceil(length / 4.0); }
    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }
}
