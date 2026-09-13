package com.ylcloud.service.knowledge.pipeline;

import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;
import com.ylcloud.entity.SpaceRagDocument;
import org.springframework.stereotype.Service;

@Service
public class KnowledgePipelineIncrementalService {
    public static final String PROFILE_SCHEMA_VERSION = "profile-v1";

    private final RagProperties ragProperties;

    public KnowledgePipelineIncrementalService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public String parserVersion() {
        return ragProperties == null || ragProperties.getExtraction() == null
                ? "structured-v3"
                : ragProperties.getExtraction().getParserVersion();
    }

    public KnowledgePipelineIncrementalDecision decide(SpaceRagDocument document,
                                                       SpaceKnowledgeDocumentProfile profile,
                                                       int sourceChunkCount,
                                                       int sourceCharacterCount,
                                                       String sourceChunkIds,
                                                       String sourceSnapshotSignature,
                                                       boolean forceRebuild) {
        if(forceRebuild) {
            return decision(SpaceConstant.KNOWLEDGE_INCREMENTAL_FORCE_REBUILD,null,null,"manual force rebuild");
        }
        if(profile == null || profile.getId() == null) {
            return decision(SpaceConstant.KNOWLEDGE_INCREMENTAL_REBUILD_PROFILE,null,null,"profile missing");
        }
        if(SpaceConstant.KNOWLEDGE_PROFILE_INVALID.equals(profile.getProfileStatus())
                || SpaceConstant.KNOWLEDGE_PROFILE_FAILED.equals(profile.getProfileStatus())) {
            return decision(SpaceConstant.KNOWLEDGE_INCREMENTAL_REBUILD_PROFILE,null,null,"profile status is not reusable");
        }
        if(!equalsValue(document.getFileHash(),profile.getSourceFileHash())) {
            return decision(SpaceConstant.KNOWLEDGE_INCREMENTAL_REBUILD_PROFILE,null,null,"file hash changed");
        }
        if(!equalsValue(parserVersion(),profile.getSourceParserVersion())) {
            return decision(SpaceConstant.KNOWLEDGE_INCREMENTAL_REBUILD_PROFILE,null,null,"parser version changed");
        }
        if(!equalsValue(PROFILE_SCHEMA_VERSION,profile.getProfileSchemaVersion())) {
            return decision(SpaceConstant.KNOWLEDGE_INCREMENTAL_REBUILD_PROFILE,null,null,"profile schema changed");
        }
        if(!equalsInt(sourceChunkCount,profile.getSourceChunkCount())
                || !equalsInt(sourceCharacterCount,profile.getSourceCharacterCount())
                || !equalsValue(sourceChunkIds,profile.getSourceChunkIds())
                || !equalsValue(sourceSnapshotSignature,profile.getSourceSnapshotSignature())) {
            return decision(SpaceConstant.KNOWLEDGE_INCREMENTAL_SYNC_RETRIEVAL_SOURCE,
                    SpaceConstant.KNOWLEDGE_PIPELINE_SYNC_RETRIEVAL_SOURCE,
                    SpaceConstant.KNOWLEDGE_TERMINAL_SOURCE_SNAPSHOT_SYNCED,
                    "document hash unchanged but retrieval source snapshot changed");
        }
        return decision(SpaceConstant.KNOWLEDGE_INCREMENTAL_SKIP_PROFILE,
                SpaceConstant.KNOWLEDGE_PIPELINE_CHECK_INCREMENTAL,
                SpaceConstant.KNOWLEDGE_TERMINAL_UNCHANGED_DOCUMENT,
                "file hash, parser version, schema version, and retrieval source snapshot unchanged");
    }

    private KnowledgePipelineIncrementalDecision decision(String action, String terminalStage, String terminalReason, String detail) {
        return new KnowledgePipelineIncrementalDecision(action,terminalStage,terminalReason,detail);
    }

    private boolean equalsValue(String left, String right) {
        String normalizedLeft = left == null ? "" : left;
        String normalizedRight = right == null ? "" : right;
        return normalizedLeft.equals(normalizedRight);
    }

    private boolean equalsInt(Integer left, Integer right) {
        return (left == null ? 0 : left) == (right == null ? 0 : right);
    }
}
