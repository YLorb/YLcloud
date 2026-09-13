package com.ylcloud.service.rag.retriever;

import org.springframework.stereotype.Component;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IkRagKeywordTokenizer implements RagKeywordTokenizer {
    private static final int MAX_PHRASE_TOKEN_LENGTH = 120;
    private static final Pattern EXACT_TOKEN = Pattern.compile(
            "(?iu)[A-Z]+/[A-Z]+\\s*\\d+(?:[-.]\\d+)*|[A-Z0-9][A-Z0-9_./:+-]{1,}[A-Z0-9]|\\d+(?:\\.\\d+)*(?:[A-Z%]+)?"
    );

    @Override
    public List<String> tokenize(String text) {
        if(text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = text.trim();
        if(normalized.length() <= MAX_PHRASE_TOKEN_LENGTH) {
            add(tokens,normalized);
        }
        addRegexTokens(tokens,normalized);
        addIkTokens(tokens,normalized);
        return new ArrayList<>(tokens);
    }

    private void addIkTokens(Set<String> tokens, String text) {
        try {
            IKSegmenter segmenter = new IKSegmenter(new StringReader(text),true);
            Lexeme lexeme;
            while((lexeme = segmenter.next()) != null) {
                add(tokens,lexeme.getLexemeText());
            }
        } catch (IOException ignored) {
            add(tokens,text);
        }
    }

    private void addRegexTokens(Set<String> tokens, String text) {
        Matcher matcher = EXACT_TOKEN.matcher(text);
        while(matcher.find()) {
            add(tokens,matcher.group());
        }
    }

    private void add(Set<String> tokens, String value) {
        if(value == null) {
            return;
        }
        String token = value.trim();
        if(token.length() < 2) {
            return;
        }
        tokens.add(token);
        tokens.add(token.toLowerCase(Locale.ROOT));
    }
}
