package com.ylcloud.service;

import com.ylcloud.entity.SpaceDissolutionEvent;
import com.ylcloud.mapper.SpaceDissolutionOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
public class SpaceDissolutionOutboxConsumer {
    private static final int BATCH_SIZE = 20;
    private static final int PROCESSING_LEASE_MINUTES = 10;
    private final SpaceDissolutionOutboxMapper outboxMapper;
    private final SpaceDissolutionDataCleanupService cleanupService;

    public SpaceDissolutionOutboxConsumer(SpaceDissolutionOutboxMapper outboxMapper,
                                          SpaceDissolutionDataCleanupService cleanupService) {
        this.outboxMapper = outboxMapper;
        this.cleanupService = cleanupService;
    }

    @Scheduled(fixedDelayString = "${ylcloud.space.dissolution.cleanup-delay-ms:10000}")
    public void processReady() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusMinutes(PROCESSING_LEASE_MINUTES);
        for (Long id : outboxMapper.listReadyIds(now,staleBefore,BATCH_SIZE)) {
            processOne(id);
        }
    }

    void processOne(Long id) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusMinutes(PROCESSING_LEASE_MINUTES);
        if (outboxMapper.claim(id,now,staleBefore) != 1) return;
        SpaceDissolutionEvent event = outboxMapper.getById(id);
        if (event == null) return;
        try {
            cleanupService.deleteVectors(event.getSpaceId());
            cleanupService.releaseReferencesAndFinalize(event.getSpaceId());
            if (outboxMapper.markSucceeded(id,LocalDateTime.now()) != 1) {
                throw new IllegalStateException("Space 解散事件终态写入冲突");
            }
            log.info("Space dissolution completed: eventId={}, spaceId={}",event.getEventId(),event.getSpaceId());
        } catch (Exception exception) {
            int retries = event.getRetryCount() == null ? 0 : event.getRetryCount();
            long delaySeconds = Math.min(3600L,30L * (1L << Math.min(retries,7)));
            String message = safeMessage(exception);
            outboxMapper.markFailed(id,LocalDateTime.now().plusSeconds(delaySeconds),message,LocalDateTime.now());
            log.warn("Space dissolution deferred: eventId={}, spaceId={}, retryInSeconds={}, reason={}",
                    event.getEventId(),event.getSpaceId(),delaySeconds,message);
        }
    }

    private String safeMessage(Exception exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) value = exception.getClass().getSimpleName();
        return value.length() <= 1000 ? value : value.substring(0,1000);
    }
}
