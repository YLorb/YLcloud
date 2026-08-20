package com.ylcloud.service;

import com.ylcloud.entity.SpaceDissolutionEvent;
import com.ylcloud.mapper.SpaceDissolutionOutboxMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpaceDissolutionOutboxConsumerTest {
    @Test
    void completesClaimedDissolutionInSafeOrder() {
        SpaceDissolutionOutboxMapper mapper = mock(SpaceDissolutionOutboxMapper.class);
        SpaceDissolutionDataCleanupService cleanup = mock(SpaceDissolutionDataCleanupService.class);
        SpaceDissolutionOutboxConsumer consumer = new SpaceDissolutionOutboxConsumer(mapper,cleanup);
        when(mapper.claim(eq(7L),any(),any())).thenReturn(1);
        when(mapper.getById(7L)).thenReturn(event());
        when(mapper.markSucceeded(eq(7L),any())).thenReturn(1);

        consumer.processOne(7L);

        verify(cleanup).deleteVectors(16L);
        verify(cleanup).releaseReferencesAndFinalize(16L);
        verify(mapper).markSucceeded(eq(7L),any());
        verify(mapper,never()).markFailed(any(),any(),any(),any());
    }

    @Test
    void failedExternalCleanupIsRetriedWithoutFinalizingSpace() {
        SpaceDissolutionOutboxMapper mapper = mock(SpaceDissolutionOutboxMapper.class);
        SpaceDissolutionDataCleanupService cleanup = mock(SpaceDissolutionDataCleanupService.class);
        SpaceDissolutionOutboxConsumer consumer = new SpaceDissolutionOutboxConsumer(mapper,cleanup);
        when(mapper.claim(eq(7L),any(),any())).thenReturn(1);
        when(mapper.getById(7L)).thenReturn(event());
        org.mockito.Mockito.doThrow(new IllegalStateException("qdrant unavailable"))
                .when(cleanup).deleteVectors(16L);

        consumer.processOne(7L);

        verify(cleanup,never()).releaseReferencesAndFinalize(any());
        verify(mapper).markFailed(eq(7L),any(),eq("qdrant unavailable"),any());
        verify(mapper,never()).markSucceeded(any(),any());
    }

    private SpaceDissolutionEvent event() {
        SpaceDissolutionEvent event = new SpaceDissolutionEvent();
        event.setId(7L);
        event.setEventId("event-7");
        event.setSpaceId(16L);
        event.setRetryCount(0);
        return event;
    }
}
