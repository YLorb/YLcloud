package com.ylcloud.service;

import com.ylcloud.entity.*;
import com.ylcloud.mapper.*;
import com.ylcloud.Exception.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** Version identity and retention policy. This service never grants user permissions. */
@Service
public class KnowledgeRevisionService {
    private final KnowledgeRevisionMapper revisions;
    private final KnowledgeReindexIntentMapper intents;
    private final SpaceFileMapper files;
    private final SpaceRagMapper configs;
    private final SpaceRagDocumentMapper documents;
    private final FileVersionMapper versions;
    private final InitialFileVersionService initialVersions;

    public KnowledgeRevisionService(KnowledgeRevisionMapper revisions,KnowledgeReindexIntentMapper intents,
                                    SpaceFileMapper files,SpaceRagMapper configs,SpaceRagDocumentMapper documents,
                                    FileVersionMapper versions,InitialFileVersionService initialVersions) {
        this.revisions=revisions; this.intents=intents; this.files=files; this.configs=configs;
        this.documents=documents; this.versions=versions; this.initialVersions=initialVersions;
    }

    /** Called within the same transaction which publishes active chunk references. */
    @Transactional
    public KnowledgeRevision recordPublication(Long spaceId,Long spaceFileId,Long documentId,List<FileRagChunk> chunks) {
        SpaceFile node=files.lockById(spaceId,spaceFileId);
        if(!active(node) || chunks==null || chunks.isEmpty()) throw new ConflictException("知识修订资源已失效");
        String hash=chunks.get(0).getFileHash();
        SpaceRagConfig activeConfig=configs.getBySpaceId(spaceId);
        if(!Objects.equals(node.getContentHash(),hash)) throw new ConflictException("文件内容已变化，拒绝发布旧修订");
        for(FileRagChunk chunk:chunks) {
            if(chunk.getId()==null || !Objects.equals(hash,chunk.getFileHash())
                    || !Objects.equals(node.getFileUuid(),chunk.getFileUuid())) {
                throw new ConflictException("索引清单包含不属于当前修订的片段");
            }
            if(activeConfig!=null && (!Objects.equals(activeConfig.getEmbeddingModel(),chunk.getEmbeddingModel())
                    || !Objects.equals(activeConfig.getChunkSize(),chunk.getChunkSize())
                    || !Objects.equals(activeConfig.getChunkOverlap(),chunk.getChunkOverlap()))) {
                throw new ConflictException("知识库索引配置已变化，拒绝发布过期构建");
            }
        }
        String sourceVersion=com.ylcloud.service.rag.parser.ImmutableDocumentProvenance.sourceVersion(chunks.get(0));
        FileVersion version=sourceVersion==null ? null : versions.findByObjectVersion(node.getFileUuid(),sourceVersion);
        if(version==null || version.getMinioVersionId()==null || version.getMinioVersionId().isBlank()) {
            throw new ConflictException("当前文件缺少不可变版本，不能发布可追溯索引");
        }
        for(FileRagChunk chunk:chunks) {
            if(!com.ylcloud.service.rag.parser.ImmutableDocumentProvenance.matches(chunk,version))
                throw new ConflictException("切片未绑定当前不可变源版本，需要重新解析");
        }
        return store(node,documentId,chunks,version);
    }

    private KnowledgeRevision store(SpaceFile node,Long documentId,List<FileRagChunk> chunks,FileVersion version) {
        LocalDateTime now=LocalDateTime.now();
        SpaceRagConfig config=configs.getBySpaceId(node.getSpaceId());
        revisions.supersede(node.getSpaceId(),node.getId(),now,expiry(now,config));
        KnowledgeRevision revision=new KnowledgeRevision();
        revision.setSpaceId(node.getSpaceId()); revision.setSpaceFileId(node.getId()); revision.setDocumentId(documentId);
        revision.setFileUuid(version.getFileUuid()); revision.setFileHash(version.getFileHash());
        revision.setFileVersionId(version.getId()); revision.setMinioVersionId(version.getMinioVersionId());
        revision.setManifestHash(manifest(chunks)); revision.setPublishedAt(now);
        revisions.insert(revision);
        for(FileRagChunk chunk:chunks) revisions.bindChunk(revision.getId(),chunk.getId());
        revisions.publish(revision.getId());
        return revision;
    }

    /** Bootstrap only authoritative live documents with an existing immutable file version. */
    @Transactional
    public KnowledgeRevision snapshotCurrentNode(Long spaceId,Long spaceFileId) {
        SpaceFile node=files.lockById(spaceId,spaceFileId);
        if(!active(node)) return null;
        KnowledgeRevision existing=revisions.currentForUpdate(spaceId,spaceFileId);
        if(existing!=null) return existing;
        SpaceRagDocument document=documents.getBySpaceFileId(spaceId,spaceFileId);
        if(document!=null) document=documents.getAnyByIdForUpdate(document.getId());
        if(!active(node) || document==null || !"SUCCESS".equals(document.getIndexStatus())
                || !"ACTIVE".equals(document.getVectorState()) || !Objects.equals(node.getContentHash(),document.getFileHash())) return null;
        List<FileRagChunk> chunks=revisions.activeDocumentChunksForUpdate(document.getId());
        if(chunks.isEmpty() || !Objects.equals(document.getChunkCount(),chunks.size())) return null;
        String sourceVersion=com.ylcloud.service.rag.parser.ImmutableDocumentProvenance.sourceVersion(chunks.get(0));
        FileVersion version=sourceVersion==null ? null : versions.findByObjectVersion(node.getFileUuid(),sourceVersion);
        if(version==null || version.getMinioVersionId()==null || version.getMinioVersionId().isBlank()) return null;
        for(FileRagChunk chunk:chunks) {
            if(!com.ylcloud.service.rag.parser.ImmutableDocumentProvenance.matches(chunk,version)) return null;
        }
        return store(node,document.getId(),chunks,version);
    }

    /** Transactional replacement for the former best-effort afterCommit callback. */
    @Transactional
    public void queueReindex(Long spaceId,Long spaceFileId,Long userId) {
        SpaceFile node=files.lockById(spaceId,spaceFileId);
        if(!active(node)) throw new ConflictException("文件不存在或已删除");
        LocalDateTime now=LocalDateTime.now();
        revisions.supersede(spaceId,spaceFileId,now,expiry(now,configs.getBySpaceId(spaceId)));
        revisions.invalidateIndex(spaceId,spaceFileId,now);
        revisions.invalidateRefs(spaceId,spaceFileId,now);
        intents.enqueue(spaceId,spaceFileId,node.getContentHash(),userId,now);
    }

    public void lockNode(Long spaceId,Long spaceFileId) {
        if(!active(files.lockById(spaceId,spaceFileId))) throw new ConflictException("知识资源已经失效");
    }

    @Transactional
    public KnowledgeRevision safeRevision(Long spaceId,Long spaceFileId,String hash,boolean forNewQuery) {
        KnowledgeRevision revision=revisions.find(spaceId,spaceFileId,hash);
        if(revision==null) {
            KnowledgeRevision bootstrapped=snapshotCurrentNode(spaceId,spaceFileId);
            if(bootstrapped!=null && Objects.equals(hash,bootstrapped.getFileHash())) revision=bootstrapped;
        }
        return readable(revision,forNewQuery) ? revision : null;
    }

    public KnowledgeRevision safeRevisionById(Long spaceId,Long spaceFileId,Long revisionId,boolean forNewQuery) {
        KnowledgeRevision revision=revisions.byId(spaceId,spaceFileId,revisionId);
        return readable(revision,forNewQuery) ? revision : null;
    }

    @Transactional
    public KnowledgeRevision revisionForChunk(Long spaceId,Long chunkId,boolean forNewQuery) {
        for(KnowledgeRevision revision:revisions.forChunk(spaceId,chunkId)) if(readable(revision,forNewQuery)) return revision;
        SpaceRagDocument document=documents.getBySpaceAndChunkId(spaceId,chunkId);
        if(document!=null) {
            KnowledgeRevision revision=snapshotCurrentNode(spaceId,document.getSpaceFileId());
            if(revision!=null && revisions.chunks(revision.getId()).stream().anyMatch(c->chunkId.equals(c.getId()))
                    && readable(revision,forNewQuery)) return revision;
        }
        return null;
    }

    public List<FileRagChunk> readableChunks(Long spaceId) {
        Map<Long,FileRagChunk> result=new LinkedHashMap<>();
        for(KnowledgeRevision revision:revisions.listBySpace(spaceId)) {
            if(revision.getSupersededAt()!=null && readable(revision,true)) {
                for(FileRagChunk chunk:revisions.chunks(revision.getId())) result.putIfAbsent(chunk.getId(),chunk);
            }
        }
        return new ArrayList<>(result.values());
    }

    private boolean readable(KnowledgeRevision revision,boolean forNewQuery) {
        if(revision==null) return false;
        SpaceFile node=files.getById(revision.getSpaceId(),revision.getSpaceFileId());
        if(!active(node) || "REMOVED".equals(node.getKnowledgeState())
                || "REMOVAL_PENDING".equals(node.getKnowledgeState())) return false;
        SpaceRagConfig config=configs.getBySpaceId(revision.getSpaceId());
        if(config==null || !Integer.valueOf(1).equals(config.getEnabled())) return false;
        KnowledgeRevision published=revisions.current(revision.getSpaceId(),revision.getSpaceFileId());
        boolean current=published!=null && Objects.equals(published.getId(),revision.getId())
                && Objects.equals(node.getContentHash(),revision.getFileHash()) && Objects.equals(node.getFileUuid(),revision.getFileUuid());
        if(current) {
            SpaceRagDocument document=documents.getBySpaceFileId(revision.getSpaceId(),revision.getSpaceFileId());
            return Integer.valueOf(1).equals(node.getSearchable()) && document!=null
                    && "SUCCESS".equals(document.getIndexStatus()) && "ACTIVE".equals(document.getVectorState())
                    && Objects.equals(document.getFileHash(),revision.getFileHash());
        }
        // Only the latest completed revision may act as a transition fallback, never every historical revision.
        boolean transition=Boolean.TRUE.equals(config.getAllowStaleDuringReindex()) && published==null
                && latestSuperseded(revision);
        if(forNewQuery) return transition;
        if(transition) return true;
        LocalDateTime end=expiry(revision.getSupersededAt(),config);
        revision.setRetainedUntil(end);
        return Boolean.TRUE.equals(config.getHistoricalEvidenceEnabled()) && end!=null && end.isAfter(LocalDateTime.now());
    }

    private boolean latestSuperseded(KnowledgeRevision candidate) {
        return revisions.listBySpace(candidate.getSpaceId()).stream()
                .filter(r->Objects.equals(r.getSpaceFileId(),candidate.getSpaceFileId()))
                .findFirst().map(r->Objects.equals(r.getId(),candidate.getId())).orElse(false);
    }

    static boolean active(SpaceFile node) {
        return node!=null && Integer.valueOf(1).equals(node.getStatus()) && "ACTIVE".equals(node.getLifecycleState());
    }

    static LocalDateTime expiry(LocalDateTime supersededAt,SpaceRagConfig config) {
        if(supersededAt==null || config==null || !Boolean.TRUE.equals(config.getHistoricalEvidenceEnabled())
                || config.getHistoryRetentionDays()==null || config.getHistoryRetentionDays()<=0) return null;
        return supersededAt.plusDays(config.getHistoryRetentionDays());
    }

    private static String manifest(List<FileRagChunk> chunks) {
        try {
            String value=chunks.stream().sorted(Comparator.comparing(FileRagChunk::getId))
                    .map(c->c.getId()+":"+c.getContentHash()).reduce("",(a,b)->a+"|"+b);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
