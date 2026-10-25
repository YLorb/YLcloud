package com.ylcloud.service.rag.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ylcloud.entity.FileRagChunk;
import com.ylcloud.entity.FileVersion;
import java.util.Objects;

/** Server-produced source identity. Legacy chunks deliberately have no trusted provenance. */
public final class ImmutableDocumentProvenance {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ImmutableDocumentProvenance() {}

    public static String stamp(String metadata,ParsedDocument source) {
        try {
            JsonNode parsed=metadata==null || metadata.isBlank() ? JSON.createObjectNode() : JSON.readTree(metadata);
            if(!(parsed instanceof ObjectNode result)) throw new IllegalArgumentException("Chunk metadata must be an object");
            result.put("sourceIdentityVersion",1);
            result.put("sourceMinioVersionId",source.getSourceMinioVersionId());
            result.put("sourceFileUuid",source.getFileUuid());
            result.put("sourceFileHash",source.getFileHash());
            return JSON.writeValueAsString(result);
        } catch(Exception ex) { throw new IllegalStateException("Cannot bind chunk to immutable source",ex); }
    }

    public static String sourceVersion(FileRagChunk chunk) {
        try {
            if(chunk==null || chunk.getMetadata()==null) return null;
            JsonNode metadata=JSON.readTree(chunk.getMetadata());
            String version=metadata.path("sourceMinioVersionId").asText("");
            if(metadata.path("sourceIdentityVersion").asInt()!=1 || version.isBlank()
                    || !Objects.equals(chunk.getFileUuid(),metadata.path("sourceFileUuid").asText(null))
                    || !Objects.equals(chunk.getFileHash(),metadata.path("sourceFileHash").asText(null))) return null;
            return version;
        } catch(Exception ignored) { return null; }
    }

    public static boolean matches(FileRagChunk chunk,FileVersion version) {
        String sourceVersion=sourceVersion(chunk);
        return sourceVersion!=null && version!=null && Objects.equals(version.getMinioVersionId(),sourceVersion)
                && Objects.equals(version.getFileUuid(),chunk.getFileUuid())
                && Objects.equals(version.getFileHash(),chunk.getFileHash());
    }
}
