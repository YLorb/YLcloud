package com.ylcloud.service.rag.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.config.RagProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class StructuredChunker {
    private static final List<String> TEXT_SEPARATORS = List.of("\n\n","\n","\u3002","\uff01","\uff1f",";","\uff1b","\uff0c",","," ");

    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    public StructuredChunker(ObjectMapper objectMapper, RagProperties ragProperties) {
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    public List<StructuredChunk> chunk(ParsedDocument document, int chunkSize, int chunkOverlap) {
        List<StructuredChunk> chunks = new ArrayList<>();
        if(document == null || document.getBlocks() == null || document.getBlocks().isEmpty()) {
            return chunks;
        }
        ChunkDraft draft = new ChunkDraft();
        for(DocumentBlock block : document.getBlocks()) {
            String blockText = formatBlock(block);
            if(blockText.isBlank()) {
                continue;
            }
            if(isStandaloneBlock(block)) {
                if(draft.length() > 0) {
                    chunks.add(toChunk(chunks.size(),draft,document));
                    draft = new ChunkDraft();
                }
                chunks.addAll(standaloneChunks(chunks.size(),block,blockText,document,chunkSize,chunkOverlap));
                continue;
            }
            if(draft.length() > 0 && shouldBreakDraft(draft,block,blockText,chunkSize)) {
                chunks.add(toChunk(chunks.size(),draft,document));
                draft = draft.tail(chunkOverlap);
            }
            if(draft.length() > 0 && draft.length() + blockText.length() + 2 > chunkSize) {
                chunks.add(toChunk(chunks.size(),draft,document));
                draft = draft.tail(chunkOverlap);
            }
            if(blockText.length() > chunkSize) {
                for(String part : recursiveSplit(blockText,chunkSize,chunkOverlap,0)) {
                    if(part.isBlank()) {
                        continue;
                    }
                    ChunkDraft single = new ChunkDraft();
                    single.add(block,part);
                    chunks.add(toChunk(chunks.size(),single,document));
                }
                draft = new ChunkDraft();
                continue;
            }
            draft.add(block,blockText);
        }
        if(draft.length() > 0) {
            chunks.add(toChunk(chunks.size(),draft,document));
        }
        return chunks;
    }

    private List<StructuredChunk> standaloneChunks(int startIndex, DocumentBlock block, String blockText,
                                                   ParsedDocument document, int chunkSize, int chunkOverlap) {
        List<String> parts = isTable(block) ? splitTable(blockText,chunkSize) : recursiveSplit(blockText,chunkSize,chunkOverlap,0);
        if(parts.isEmpty()) {
            parts = List.of(blockText);
        }
        List<StructuredChunk> result = new ArrayList<>();
        for(int i = 0; i < parts.size(); i++) {
            ChunkDraft draft = new ChunkDraft();
            draft.add(block,parts.get(i));
            if(isTable(block)) {
                draft.tableId = tableId(block);
                draft.tablePartIndex = i + 1;
                draft.tablePartCount = parts.size();
            }
            result.add(toChunk(startIndex + i,draft,document));
        }
        return result;
    }

    private List<String> splitTable(String tableText, int chunkSize) {
        String[] lines = tableText.split("\\R");
        if(lines.length <= 3) {
            return recursiveSplit(tableText,chunkSize,0,0);
        }
        List<String> header = new ArrayList<>();
        header.add(lines[0]);
        int rowStart = 1;
        if(lines.length > 1 && lines[1].matches("\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*")) {
            header.add(lines[1]);
            rowStart = 2;
        }
        List<String> result = new ArrayList<>();
        String headerText = String.join("\n",header);
        StringBuilder current = new StringBuilder(headerText);
        for(int i = rowStart; i < lines.length; i++) {
            String row = lines[i];
            if(current.length() + row.length() + 1 > chunkSize && current.length() > headerText.length()) {
                result.add(current.toString());
                current = new StringBuilder(headerText);
            }
            if(current.length() > 0) {
                current.append('\n');
            }
            current.append(row);
        }
        if(current.length() > headerText.length()) {
            result.add(current.toString());
        }
        return result.isEmpty() ? List.of(tableText) : result;
    }

    private List<String> recursiveSplit(String text, int chunkSize, int chunkOverlap, int depth) {
        if(text == null || text.isBlank()) {
            return List.of();
        }
        if(text.length() <= chunkSize) {
            return List.of(text.trim());
        }
        if(depth >= TEXT_SEPARATORS.size()) {
            return splitByLength(text,chunkSize,chunkOverlap);
        }
        String separator = TEXT_SEPARATORS.get(depth);
        if(!text.contains(separator)) {
            return recursiveSplit(text,chunkSize,chunkOverlap,depth + 1);
        }
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] parts = text.split(java.util.regex.Pattern.quote(separator));
        for(String raw : parts) {
            String part = raw.trim();
            if(part.isBlank()) {
                continue;
            }
            String candidate = separator.trim().isEmpty() ? part : part + separator;
            if(candidate.length() > chunkSize) {
                if(current.length() > 0) {
                    result.add(current.toString().trim());
                    current = new StringBuilder(overlapTail(current.toString(),chunkOverlap));
                }
                result.addAll(recursiveSplit(candidate,chunkSize,chunkOverlap,depth + 1));
                continue;
            }
            if(current.length() + candidate.length() > chunkSize && current.length() > 0) {
                result.add(current.toString().trim());
                current = new StringBuilder(overlapTail(current.toString(),chunkOverlap));
            }
            current.append(candidate);
        }
        if(current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private List<String> splitByLength(String text, int chunkSize, int chunkOverlap) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while(start < text.length()) {
            int end = Math.min(start + chunkSize,text.length());
            result.add(text.substring(start,end).trim());
            if(end >= text.length()) {
                break;
            }
            start = Math.max(end - chunkOverlap,start + 1);
        }
        return result;
    }

    private String formatBlock(DocumentBlock block) {
        String text = block.getText() == null ? "" : block.getText().trim();
        if(text.isBlank()) {
            return "";
        }
        if(isTable(block)) {
            return text;
        }
        if(block.getHeadingPath() != null && !block.getHeadingPath().isEmpty() && !"heading".equals(block.getType())) {
            return "Document path: " + String.join(" > ",block.getHeadingPath()) + "\n\n" + text;
        }
        return text;
    }

    private boolean isStandaloneBlock(DocumentBlock block) {
        if(block == null || block.getType() == null) {
            return false;
        }
        if(isTable(block)) {
            return true;
        }
        return Boolean.TRUE.equals(ragProperties.getChunking().getKeepCodeBlocks())
                && "code".equalsIgnoreCase(block.getType());
    }

    private boolean isTable(DocumentBlock block) {
        return block != null && "table".equalsIgnoreCase(block.getType());
    }

    private boolean shouldBreakDraft(ChunkDraft draft, DocumentBlock block, String blockText, int chunkSize) {
        if(block != null && "heading".equalsIgnoreCase(block.getType())) {
            return true;
        }
        if(!Boolean.TRUE.equals(ragProperties.getChunking().getSemanticEnabled())) {
            return false;
        }
        if(draft.length() < 120 || blockText.length() < 40) {
            return false;
        }
        if(draft.length() + blockText.length() + 2 > chunkSize) {
            return true;
        }
        return lexicalOverlap(draft.content.toString(),blockText) < semanticThreshold();
    }

    private double semanticThreshold() {
        return ragProperties.getChunking().getSemanticBreakThreshold() == null ? 0.28
                : ragProperties.getChunking().getSemanticBreakThreshold();
    }

    private double lexicalOverlap(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if(leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 1.0;
        }
        int overlap = 0;
        for(String token : leftTokens) {
            if(rightTokens.contains(token)) {
                overlap++;
            }
        }
        return overlap * 1.0 / Math.sqrt(leftTokens.size() * rightTokens.size());
    }

    private Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        if(value == null) {
            return result;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[a-zA-Z0-9_]{2,}|[\u4e00-\u9fff]").matcher(value.toLowerCase());
        while(matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private String tableId(DocumentBlock block) {
        Integer index = block.getOrderIndex() == null ? 0 : block.getOrderIndex();
        Integer page = block.getPageNo() == null ? 0 : block.getPageNo();
        return "p" + page + "-table-" + index;
    }

    private StructuredChunk toChunk(int index, ChunkDraft draft, ParsedDocument document) {
        StructuredChunk chunk = new StructuredChunk();
        String content = draft.content.toString().trim();
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setContentHash(sha256(content));
        chunk.setTokenCount(content.length());
        chunk.setPageStart(draft.pageStart);
        chunk.setPageEnd(draft.pageEnd);
        chunk.setHeadingPath(new ArrayList<>(draft.headingPath));
        chunk.setBlockTypes(new ArrayList<>(draft.blockTypes));
        chunk.setMetadataJson(metadataJson(document,draft));
        return chunk;
    }

    private String metadataJson(ParsedDocument document, ChunkDraft draft) {
        try {
            return objectMapper.writeValueAsString(new Metadata(
                    document.getParser(),
                    document.getParserVersion(),
                    document.isFallback(),
                    vlmEnabled(),
                    draft.sources.contains("vlm") || draft.sources.contains("vlm-page"),
                    draft.pageStart,
                    draft.pageEnd,
                    new ArrayList<>(draft.headingPath),
                    new ArrayList<>(draft.blockTypes),
                    new ArrayList<>(draft.sources),
                    draft.tableId,
                    draft.tablePartIndex,
                    draft.tablePartCount,
                    draft.tablePartCount != null && draft.tablePartCount > 1
            ));
        } catch (Exception ex) {
            return "{}";
        }
    }

    private boolean vlmEnabled() {
        return ragProperties.getExtraction() != null && Boolean.TRUE.equals(ragProperties.getExtraction().getVlmEnabled());
    }

    private String overlapTail(String value, int chunkOverlap) {
        if(chunkOverlap <= 0 || value.length() <= chunkOverlap) {
            return "";
        }
        return value.substring(value.length() - chunkOverlap);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for(byte b : encoded) {
                builder.append(String.format("%02x",b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available",ex);
        }
    }

    private record Metadata(String parser, String parserVersion, boolean fallback, boolean vlmEnabled,
                            boolean vlmUsed, Integer pageStart, Integer pageEnd, List<String> headingPath,
                            List<String> blockTypes, List<String> sources, String tableId,
                            Integer tablePartIndex, Integer tablePartCount, boolean preserveTableHeader) {
    }

    private static class ChunkDraft {
        private final StringBuilder content = new StringBuilder();
        private final Set<String> headingPath = new LinkedHashSet<>();
        private final Set<String> blockTypes = new LinkedHashSet<>();
        private final Set<String> sources = new LinkedHashSet<>();
        private Integer pageStart;
        private Integer pageEnd;
        private String tableId;
        private Integer tablePartIndex;
        private Integer tablePartCount;

        void add(DocumentBlock block, String text) {
            if(content.length() > 0) {
                content.append("\n\n");
            }
            content.append(text);
            if(block.getHeadingPath() != null) {
                headingPath.addAll(block.getHeadingPath());
            }
            if(block.getType() != null) {
                blockTypes.add(block.getType());
            }
            if(block.getSource() != null) {
                sources.add(block.getSource());
            }
            if(block.getPageNo() != null) {
                pageStart = pageStart == null ? block.getPageNo() : Math.min(pageStart,block.getPageNo());
                pageEnd = pageEnd == null ? block.getPageNo() : Math.max(pageEnd,block.getPageNo());
            }
        }

        int length() {
            return content.length();
        }

        ChunkDraft tail(int chunkOverlap) {
            ChunkDraft draft = new ChunkDraft();
            if(chunkOverlap <= 0 || content.length() <= chunkOverlap) {
                return draft;
            }
            draft.content.append(content.substring(content.length() - chunkOverlap));
            draft.headingPath.addAll(headingPath);
            draft.blockTypes.addAll(blockTypes);
            draft.sources.addAll(sources);
            draft.pageStart = pageStart;
            draft.pageEnd = pageEnd;
            return draft;
        }
    }
}
