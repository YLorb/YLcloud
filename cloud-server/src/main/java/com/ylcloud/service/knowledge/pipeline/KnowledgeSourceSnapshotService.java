package com.ylcloud.service.knowledge.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ylcloud.entity.FileRagChunk;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class KnowledgeSourceSnapshotService {
    private final ObjectMapper objectMapper;

    public KnowledgeSourceSnapshotService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public KnowledgeSourceSnapshot create(List<FileRagChunk> chunks, int sourceCharacterCount, String parserVersion) {
        List<FileRagChunk> ordered = new ArrayList<>(chunks == null ? List.of() : chunks);
        ordered.sort(Comparator.comparing(FileRagChunk::getChunkIndex,Comparator.nullsLast(Integer::compareTo))
                .thenComparing(chunk -> value(chunk.getContentHash()))
                .thenComparing(chunk -> value(chunk.getContent()))
                .thenComparing(chunk -> value(chunk.getMetadata())));
        List<String> chunkIds = ordered.stream().map(chunk -> String.valueOf(chunk.getId())).toList();
        MessageDigest digest = sha256();
        update(digest,parserVersion);
        for(FileRagChunk chunk : ordered) {
            update(digest,chunk.getChunkIndex() == null ? "" : String.valueOf(chunk.getChunkIndex()));
            update(digest,chunk.getContentHash());
            update(digest,chunk.getContent());
            update(digest,normalizeMetadata(chunk.getMetadata()));
        }
        return new KnowledgeSourceSnapshot(toJson(chunkIds),ordered.size(),sourceCharacterCount,parserVersion,toHex(digest.digest()));
    }

    private String normalizeMetadata(String metadata) {
        if(metadata == null || metadata.isBlank()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(sort(objectMapper.readTree(metadata)));
        } catch (JsonProcessingException ignored) {
            return metadata.trim();
        }
    }

    private JsonNode sort(JsonNode node) {
        if(node == null || node.isValueNode()) {
            return node;
        }
        if(node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(child -> result.add(sort(child)));
            return result;
        }
        ObjectNode result = objectMapper.createObjectNode();
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        fields.stream().sorted().forEach(field -> result.set(field,sort(node.get(field))));
        return result;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize knowledge source chunk ids",ex);
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable",ex);
        }
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for(byte value : bytes) {
            result.append(String.format("%02x",value & 0xff));
        }
        return result.toString();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
