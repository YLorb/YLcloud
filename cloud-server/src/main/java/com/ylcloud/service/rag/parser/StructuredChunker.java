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
    private static final int FALLBACK_CHUNK_SIZE = 800;
    private static final int FALLBACK_CHUNK_OVERLAP = 100;

    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    public StructuredChunker(ObjectMapper objectMapper, RagProperties ragProperties) {
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    public List<StructuredChunk> chunk(ParsedDocument document, int chunkSize, int chunkOverlap) {
        List<StructuredChunk> childChunks = buildChildChunks(document,chunkSize,chunkOverlap);
        if(childChunks.isEmpty()) {
            return fixedWindowChunks(document);
        }
        return withParentChunks(document,childChunks,chunkSize);
    }

    private List<StructuredChunk> buildChildChunks(ParsedDocument document, int chunkSize, int chunkOverlap) {
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

    private List<StructuredChunk> withParentChunks(ParsedDocument document, List<StructuredChunk> childChunks, int chunkSize) {
        List<StructuredChunk> result = new ArrayList<>();
        java.util.Map<String, List<StructuredChunk>> groups = new java.util.LinkedHashMap<>();
        for(StructuredChunk child : childChunks) {
            groups.computeIfAbsent(parentKey(child),key -> new ArrayList<>()).add(child);
        }
        java.util.Map<String, StructuredChunk> parents = new java.util.LinkedHashMap<>();
        int index = 0;
        int parentSize = Math.max(chunkSize,FALLBACK_CHUNK_SIZE) * 3;
        for(java.util.Map.Entry<String, List<StructuredChunk>> entry : groups.entrySet()) {
            StructuredChunk parent = parentChunk(index++,entry.getKey(),entry.getValue(),document,parentSize);
            parents.put(entry.getKey(),parent);
            result.add(parent);
        }
        for(StructuredChunk child : childChunks) {
            StructuredChunk parent = parents.get(parentKey(child));
            child.setChunkIndex(index++);
            child.setChunkType("child");
            child.setParentChunkIndex(parent == null ? null : parent.getChunkIndex());
            child.setParentContentHash(parent == null ? null : parent.getContentHash());
            child.setChunkLevel(child.getHeadingPath() == null ? 0 : child.getHeadingPath().size() + 1);
            child.setMetadataJson(enrichMetadata(child.getMetadataJson(),child,false,"structured"));
            result.add(child);
        }
        return result;
    }

    private StructuredChunk parentChunk(int index, String key, List<StructuredChunk> children,
                                        ParsedDocument document, int parentSize) {
        StructuredChunk parent = new StructuredChunk();
        String content = parentContent(key,children,parentSize);
        parent.setChunkIndex(index);
        parent.setChunkType("parent");
        parent.setChunkLevel(parentLevel(children));
        parent.setContent(content);
        parent.setContentHash(sha256(content));
        parent.setTokenCount(content.length());
        parent.setPageStart(minPage(children));
        parent.setPageEnd(maxPage(children));
        parent.setHeadingPath(parentHeading(children));
        parent.setBlockTypes(parentBlockTypes(children));
        parent.setMetadataJson(enrichMetadata(parentMetadata(document,parent,children),parent,false,"parent_section"));
        return parent;
    }

    private String parentContent(String key, List<StructuredChunk> children, int parentSize) {
        StringBuilder builder = new StringBuilder();
        if(key != null && !key.isBlank() && !"__root__".equals(key)) {
            builder.append("Section: ").append(key).append("\n\n");
        }
        for(StructuredChunk child : children) {
            if(child.getContent() == null || child.getContent().isBlank()) {
                continue;
            }
            if(builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(child.getContent());
            if(builder.length() >= parentSize) {
                break;
            }
        }
        String value = builder.toString().trim();
        return value.length() <= parentSize ? value : value.substring(0,parentSize);
    }

    private String parentKey(StructuredChunk chunk) {
        if(chunk.getHeadingPath() == null || chunk.getHeadingPath().isEmpty()) {
            return "__root__";
        }
        return String.join(" > ",chunk.getHeadingPath());
    }

    private int parentLevel(List<StructuredChunk> children) {
        List<String> heading = parentHeading(children);
        return heading == null ? 0 : heading.size();
    }

    private List<String> parentHeading(List<StructuredChunk> children) {
        for(StructuredChunk child : children) {
            if(child.getHeadingPath() != null && !child.getHeadingPath().isEmpty()) {
                return new ArrayList<>(child.getHeadingPath());
            }
        }
        return new ArrayList<>();
    }

    private List<String> parentBlockTypes(List<StructuredChunk> children) {
        Set<String> types = new LinkedHashSet<>();
        for(StructuredChunk child : children) {
            if(child.getBlockTypes() != null) {
                types.addAll(child.getBlockTypes());
            }
        }
        return new ArrayList<>(types);
    }

    private Integer minPage(List<StructuredChunk> children) {
        Integer result = null;
        for(StructuredChunk child : children) {
            if(child.getPageStart() == null) continue;
            result = result == null ? child.getPageStart() : Math.min(result,child.getPageStart());
        }
        return result;
    }

    private Integer maxPage(List<StructuredChunk> children) {
        Integer result = null;
        for(StructuredChunk child : children) {
            if(child.getPageEnd() == null) continue;
            result = result == null ? child.getPageEnd() : Math.max(result,child.getPageEnd());
        }
        return result;
    }

    private List<StructuredChunk> fixedWindowChunks(ParsedDocument document) {
        String text = document == null || document.getFullText() == null ? "" : document.getFullText().trim();
        if(text.isBlank()) {
            return List.of();
        }
        List<StructuredChunk> chunks = new ArrayList<>();
        List<String> parts = splitByLength(text,FALLBACK_CHUNK_SIZE,FALLBACK_CHUNK_OVERLAP);
        for(int i = 0; i < parts.size(); i++) {
            StructuredChunk chunk = new StructuredChunk();
            String content = parts.get(i);
            chunk.setChunkIndex(i);
            chunk.setChunkType("child");
            chunk.setChunkLevel(0);
            chunk.setContent(content);
            chunk.setContentHash(sha256(content));
            chunk.setTokenCount(content.length());
            chunk.setMetadataJson(enrichMetadata(fallbackMetadata(document,i,parts.size()),chunk,true,"fixed_window"));
            chunks.add(chunk);
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
        chunk.setChunkType("child");
        chunk.setChunkLevel(draft.headingPath.size() + 1);
        chunk.setContent(content);
        chunk.setContentHash(sha256(content));
        chunk.setTokenCount(content.length());
        chunk.setPageStart(draft.pageStart);
        chunk.setPageEnd(draft.pageEnd);
        chunk.setHeadingPath(new ArrayList<>(draft.headingPath));
        chunk.setBlockTypes(new ArrayList<>(draft.blockTypes));
        chunk.setMetadataJson(metadataJson(document,draft,chunk));
        return chunk;
    }

    private String metadataJson(ParsedDocument document, ChunkDraft draft, StructuredChunk chunk) {
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
                    draft.tablePartCount != null && draft.tablePartCount > 1,
                    chunk.getChunkType(),
                    chunk.getParentChunkIndex(),
                    chunk.getParentContentHash(),
                    chunk.getChunkLevel(),
                    false,
                    "structured"
            ));
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String parentMetadata(ParsedDocument document, StructuredChunk parent, List<StructuredChunk> children) {
        try {
            return objectMapper.writeValueAsString(new Metadata(
                    document == null ? "unknown" : document.getParser(),
                    document == null ? "structured-v1" : document.getParserVersion(),
                    document != null && document.isFallback(),
                    vlmEnabled(),
                    containsSource(children,"vlm") || containsSource(children,"vlm-page"),
                    parent.getPageStart(),
                    parent.getPageEnd(),
                    parent.getHeadingPath(),
                    parent.getBlockTypes(),
                    List.of("parent-section"),
                    null,
                    null,
                    null,
                    false,
                    parent.getChunkType(),
                    parent.getParentChunkIndex(),
                    parent.getParentContentHash(),
                    parent.getChunkLevel(),
                    false,
                    "parent_section"
            ));
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String fallbackMetadata(ParsedDocument document, int partIndex, int partCount) {
        try {
            return objectMapper.writeValueAsString(new Metadata(
                    document == null ? "unknown" : document.getParser(),
                    document == null ? "structured-v1" : document.getParserVersion(),
                    document != null && document.isFallback(),
                    vlmEnabled(),
                    false,
                    null,
                    null,
                    List.of(),
                    List.of("fallback_text"),
                    List.of("fixed-window"),
                    null,
                    partIndex + 1,
                    partCount,
                    false,
                    "child",
                    null,
                    null,
                    0,
                    true,
                    "fixed_window"
            ));
        } catch (Exception ex) {
            return "{}";
        }
    }

    private boolean containsSource(List<StructuredChunk> children, String source) {
        if(children == null || source == null) {
            return false;
        }
        for(StructuredChunk child : children) {
            String metadata = child.getMetadataJson();
            if(metadata != null && metadata.contains(source)) {
                return true;
            }
        }
        return false;
    }

    private String enrichMetadata(String metadataJson, StructuredChunk chunk, boolean fallbackChunking, String strategy) {
        try {
            java.util.Map<String, Object> metadata = metadataJson == null || metadataJson.isBlank()
                    ? new java.util.LinkedHashMap<>()
                    : objectMapper.readValue(metadataJson,new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() {});
            metadata.put("chunkType",chunk.getChunkType());
            metadata.put("parentChunkIndex",chunk.getParentChunkIndex());
            metadata.put("parentContentHash",chunk.getParentContentHash());
            metadata.put("chunkLevel",chunk.getChunkLevel());
            metadata.put("fallbackChunking",fallbackChunking);
            metadata.put("chunkingStrategy",strategy);
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            return "{\"chunkType\":\"" + escapeJson(chunk.getChunkType()) + "\",\"fallbackChunking\":" + fallbackChunking
                    + ",\"chunkingStrategy\":\"" + escapeJson(strategy) + "\"}";
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

    private String escapeJson(String value) {
        if(value == null) {
            return "";
        }
        return value.replace("\\","\\\\").replace("\"","\\\"");
    }

    private record Metadata(String parser, String parserVersion, boolean fallback, boolean vlmEnabled,
                            boolean vlmUsed, Integer pageStart, Integer pageEnd, List<String> headingPath,
                            List<String> blockTypes, List<String> sources, String tableId,
                            Integer tablePartIndex, Integer tablePartCount, boolean preserveTableHeader,
                            String chunkType, Integer parentChunkIndex, String parentContentHash,
                            Integer chunkLevel, boolean fallbackChunking, String chunkingStrategy) {
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
