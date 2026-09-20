package com.ylcloud.share;

import lombok.Data;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public final class ShareModels {
    private ShareModels() {}
    @Data public static class Link {
        private Long id, creatorId, sourceId, spaceId, contentRevision, maxDownloads, downloadCount, version;
        private String sourceType, fileUuid, shortCode, passwordHash;
        private boolean passwordEnabled, forceDownload;
        private LocalDateTime expiresAt, revokedAt, createdAt, updatedAt;
    }
    @Data public static class Node {
        private Long id, ownerId, spaceId, parentId, revision, size;
        private String name, uuid, lifecycleState;
        private int dir, status;
        private LocalDateTime replacedAt;
    }
    public record Conditions(String expiryMode, Integer days, OffsetDateTime expiresAt,
                             Long maxDownloads, boolean passwordEnabled, String password,
                             boolean forceDownload, Long version) {}
    public record Create(String sourceType, Long sourceId, Long spaceId, Conditions conditions) {}
    public record View(Long id, String shortCode, String sourceType, Long sourceId, Long spaceId,
                       Long creatorId, String name, boolean directory, String expiresAt,
                       Long maxDownloads, long downloadCount, boolean passwordEnabled,
                       boolean forceDownload, String state, long version) {}
    public record Open(String state, String message, String name, boolean directory, Long size,
                       boolean forceDownload, String visitToken, String downloadToken) {}
    public record Verify(String password, String visitToken) {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public static class Expired extends RuntimeException {
        public Expired() { super("文件已过期"); }
    }
}
