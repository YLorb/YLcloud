package com.ylcloud.service;

import com.ylcloud.entity.*;
import com.ylcloud.mapper.*;
import com.ylcloud.service.space.SpaceRagService;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Persistent transactional intent dispatcher. Duplicate delivery is harmless to domain task creation. */
@Service
public class KnowledgeReindexDispatcher {
    private final KnowledgeReindexIntentMapper intents;
    private final SpaceFileMapper files;
    private final SpaceRagService rag;
    public KnowledgeReindexDispatcher(KnowledgeReindexIntentMapper intents,SpaceFileMapper files,@Lazy SpaceRagService rag) {
        this.intents=intents; this.files=files; this.rag=rag;
    }
    @Scheduled(fixedDelayString="${ylcloud.rag.reindex-intent-delay-ms:10000}")
    public void dispatch() {
        LocalDateTime now=LocalDateTime.now();
        intents.recover(now.minusMinutes(10),now);
        for(KnowledgeReindexIntent intent:intents.pending(now)) {
            String token=UUID.randomUUID().toString();
            if(intents.claim(intent.getId(),token,now)!=1) continue;
            try {
                SpaceFile node=files.getById(intent.getSpaceId(),intent.getSpaceFileId());
                if(!KnowledgeRevisionService.active(node) || !Objects.equals(node.getContentHash(),intent.getFileHash())) {
                    intents.finish(intent.getId(),token,"OBSOLETE",null,null,LocalDateTime.now()); continue;
                }
                rag.dispatchRevisionRebuild(intent.getSpaceId(),intent.getSpaceFileId(),intent.getFileHash(),intent.getCreatedBy());
                intents.finish(intent.getId(),token,"DISPATCHED",null,null,LocalDateTime.now());
            } catch(Exception ex) {
                String error=ex.getMessage()==null ? ex.getClass().getSimpleName() : ex.getMessage();
                intents.finish(intent.getId(),token,"RETRY",error.substring(0,Math.min(1000,error.length())),
                        LocalDateTime.now().plusSeconds(30),LocalDateTime.now());
            }
        }
    }
}
