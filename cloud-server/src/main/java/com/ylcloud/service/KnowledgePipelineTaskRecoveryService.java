package com.ylcloud.service;

import com.ylcloud.config.RagProperties;
import com.ylcloud.async.mq.AsyncMqProperties;
import com.ylcloud.entity.SpaceKnowledgePipelineTask;
import com.ylcloud.mapper.SpaceKnowledgePipelineTaskMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

/** Reclaims knowledge-profile tasks left active by a process crash. */
@Service
public class KnowledgePipelineTaskRecoveryService {
    private final SpaceKnowledgePipelineTaskMapper taskMapper;
    private final RagProperties ragProperties;
    private AsyncMqProperties mqProperties;

    public KnowledgePipelineTaskRecoveryService(SpaceKnowledgePipelineTaskMapper taskMapper,
                                                RagProperties ragProperties) {
        this.taskMapper = taskMapper;
        this.ragProperties = ragProperties;
    }

    @Autowired(required = false)
    public void setMqProperties(AsyncMqProperties mqProperties) {
        this.mqProperties=mqProperties;
    }

    @Scheduled(fixedDelayString = "${ylcloud.rag.index.profile-recovery-delay-ms:60000}",
            initialDelayString = "${ylcloud.rag.index.profile-recovery-initial-delay-ms:30000}")
    public void expireStaleTasks() {
        Integer configured = ragProperties.getIndex() == null
                ? null : ragProperties.getIndex().getProfileTaskTimeoutMinutes();
        int timeoutMinutes = Math.max(5,configured == null ? 30 : configured);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        String error = "Knowledge profile task timeout after " + timeoutMinutes + " minutes";
        for(SpaceKnowledgePipelineTask task : taskMapper.listStaleActive(cutoff,200)) {
            if(mqProperties!=null && mqProperties.isEnabled() && mqProperties.isKnowledge()
                    && task.getAsyncTaskId()!=null) continue;
            taskMapper.failActive(task.getId(),error,LocalDateTime.now());
        }
    }
}
