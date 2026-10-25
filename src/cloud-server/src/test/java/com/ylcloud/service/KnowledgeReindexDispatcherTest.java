package com.ylcloud.service;

import com.ylcloud.entity.*;
import com.ylcloud.mapper.*;
import com.ylcloud.service.space.SpaceRagService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeReindexDispatcherTest {
    final KnowledgeReindexIntentMapper intents=mock(KnowledgeReindexIntentMapper.class);
    final SpaceFileMapper files=mock(SpaceFileMapper.class);
    final SpaceRagService rag=mock(SpaceRagService.class);
    final KnowledgeReindexDispatcher service=new KnowledgeReindexDispatcher(intents,files,rag);

    @Test void obsoleteContentDoesNotScheduleNewWork() {
        setup("new"); service.dispatch();
        verifyNoInteractions(rag);
        verify(intents).finish(eq(3L),anyString(),eq("OBSOLETE"),isNull(),isNull(),any());
    }
    @Test void temporarySchedulingFailureRemainsRetryable() {
        setup("old"); doThrow(new IllegalStateException("offline")).when(rag).dispatchRevisionRebuild(1L,2L,"old",4L);
        service.dispatch();
        verify(intents).finish(eq(3L),anyString(),eq("RETRY"),eq("offline"),notNull(),any());
    }
    @Test void competingDispatcherCannotExecuteWithoutClaim() {
        setup("old"); when(intents.claim(eq(3L),anyString(),any())).thenReturn(0); service.dispatch(); verifyNoInteractions(rag);
    }
    private void setup(String currentHash) {
        KnowledgeReindexIntent intent=new KnowledgeReindexIntent(); intent.setId(3L); intent.setSpaceId(1L); intent.setSpaceFileId(2L);
        intent.setFileHash("old"); intent.setCreatedBy(4L);
        when(intents.pending(any())).thenReturn(List.of(intent)); when(intents.claim(eq(3L),anyString(),any())).thenReturn(1);
        SpaceFile node=new SpaceFile(); node.setStatus(1); node.setLifecycleState("ACTIVE"); node.setContentHash(currentHash);
        when(files.getById(1L,2L)).thenReturn(node);
    }
}
