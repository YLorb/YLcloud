package com.ylcloud.service.knowledge.pipeline;

import com.ylcloud.config.RagProperties;
import com.ylcloud.constant.SpaceConstant;
import com.ylcloud.entity.SpaceKnowledgeDocumentProfile;
import com.ylcloud.entity.SpaceRagDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgePipelineIncrementalServiceTest {
    private final KnowledgePipelineIncrementalService service = new KnowledgePipelineIncrementalService(new RagProperties());

    @Test
    void skipsProfileWhenSignatureIsUnchanged() {
        KnowledgePipelineIncrementalDecision decision = service.decide(document("hash-1"),profile("hash-1",SpaceConstant.KNOWLEDGE_PROFILE_VALID),3,120,false);

        assertEquals(SpaceConstant.KNOWLEDGE_INCREMENTAL_SKIP_PROFILE,decision.action());
        assertEquals(SpaceConstant.KNOWLEDGE_TERMINAL_UNCHANGED_DOCUMENT,decision.terminalReason());
    }

    @Test
    void failedProfileAlwaysRebuilds() {
        KnowledgePipelineIncrementalDecision decision = service.decide(document("hash-1"),profile("hash-1",SpaceConstant.KNOWLEDGE_PROFILE_INVALID),3,120,false);

        assertEquals(SpaceConstant.KNOWLEDGE_INCREMENTAL_REBUILD_PROFILE,decision.action());
    }

    @Test
    void unchangedHashWithChangedChunksRefreshesRetrievalOnly() {
        KnowledgePipelineIncrementalDecision decision = service.decide(document("hash-1"),profile("hash-1",SpaceConstant.KNOWLEDGE_PROFILE_VALID),4,180,false);

        assertEquals(SpaceConstant.KNOWLEDGE_INCREMENTAL_REBUILD_RETRIEVAL_ONLY,decision.action());
        assertEquals(SpaceConstant.KNOWLEDGE_TERMINAL_RETRIEVAL_ONLY,decision.terminalReason());
    }

    private SpaceRagDocument document(String hash) {
        SpaceRagDocument document = new SpaceRagDocument();
        document.setFileHash(hash);
        return document;
    }

    private SpaceKnowledgeDocumentProfile profile(String hash, String status) {
        SpaceKnowledgeDocumentProfile profile = new SpaceKnowledgeDocumentProfile();
        profile.setId(1L);
        profile.setProfileStatus(status);
        profile.setSourceFileHash(hash);
        profile.setSourceParserVersion(service.parserVersion());
        profile.setProfileSchemaVersion(KnowledgePipelineIncrementalService.PROFILE_SCHEMA_VERSION);
        profile.setSourceChunkCount(3);
        profile.setSourceCharacterCount(120);
        return profile;
    }
}
