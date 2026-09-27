package com.ylcloud.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Location in the parser's fullText, using zero-based UTF-16 offsets and an exclusive end. */
public final class ChunkEvidenceMetadata {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChunkEvidenceMetadata() { }

    public static String enrich(String metadata, Long chunkId, String content, String fullText) {
        ObjectNode node = parse(metadata);
        if (chunkId != null) {
            node.put("chunkId", chunkId);
            node.put("chunk_id", chunkId);
        }
        Integer page = page(node);
        if (page == null) node.putNull("page");
        else node.put("page", page);

        String locatable = locatableText(content);
        Integer start = integer(node.get("offsetStart"));
        Integer end = integer(node.get("offsetEnd"));
        if (!validRange(fullText, locatable, start, end)) {
            start = uniqueStart(fullText, locatable);
            end = start == null ? null : start + locatable.length();
        }
        if (start == null) {
            node.putNull("offsetStart");
            node.putNull("offsetEnd");
            node.putNull("offset_start");
            node.putNull("offset_end");
        } else {
            node.put("offsetStart", start);
            node.put("offsetEnd", end);
            node.put("offset_start", start);
            node.put("offset_end", end);
        }
        return node.toString();
    }

    public static Location location(String metadata) {
        ObjectNode node = parse(metadata);
        Integer start = integer(node.get("offsetStart"));
        Integer end = integer(node.get("offsetEnd"));
        return new Location(page(node), start == null ? integer(node.get("offset_start")) : start,
                end == null ? integer(node.get("offset_end")) : end);
    }

    private static ObjectNode parse(String metadata) {
        if (metadata != null && !metadata.isBlank()) {
            try {
                JsonNode node = MAPPER.readTree(metadata);
                if (node instanceof ObjectNode object) return object;
            } catch (Exception ignored) {
                // Legacy or malformed metadata still gets a usable location envelope.
            }
        }
        return MAPPER.createObjectNode();
    }

    private static Integer page(ObjectNode node) {
        Integer explicit = integer(node.get("page"));
        if (explicit != null) return explicit;
        Integer start = integer(node.get("pageStart"));
        Integer end = integer(node.get("pageEnd"));
        return start != null && start.equals(end) ? start : null;
    }

    private static Integer integer(JsonNode value) {
        return value != null && value.canConvertToInt() ? value.intValue() : null;
    }

    private static boolean validRange(String fullText, String content, Integer start, Integer end) {
        return fullText != null && content != null && start != null && end != null
                && start >= 0 && end <= fullText.length() && end - start == content.length()
                && fullText.regionMatches(start, content, 0, content.length());
    }

    private static Integer uniqueStart(String fullText, String content) {
        if (fullText == null || content == null || content.isBlank()) return null;
        int first = fullText.indexOf(content);
        return first >= 0 && fullText.indexOf(content, first + 1) < 0 ? first : null;
    }

    private static String locatableText(String content) {
        if (content != null && content.startsWith("Document path: ")) {
            int separator = content.indexOf("\n\n");
            if (separator >= 0) return content.substring(separator + 2);
        }
        return content;
    }

    public record Location(Integer page, Integer offsetStart, Integer offsetEnd) { }
}
