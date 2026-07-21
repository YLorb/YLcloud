package com.ylcloud.workflow.tool;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.mapper.FileRagChunkMapper;
import com.ylcloud.service.SpacePermissionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class WorkflowBuiltinToolConfigurationTest {
    @Test
    void knowledgeChunkLoadChecksMembershipBeforeReadingAnyChunk() {
        SpacePermissionService permission = mock(SpacePermissionService.class);
        FileRagChunkMapper chunks = mock(FileRagChunkMapper.class);
        doThrow(new BaseException(403, "forbidden")).when(permission).requireMember(5L, 7L);
        WorkflowToolHandler handler = new WorkflowBuiltinToolConfiguration().knowledgeLoadChunks(permission, chunks);
        ToolInvocationContext context = new ToolInvocationContext(UUID.randomUUID(), UUID.randomUUID(), "node",
                UUID.randomUUID(), 7, 8);

        assertThrows(BaseException.class, () -> handler.invoke(context, Map.of("spaceId", 5, "chunkIds", List.of(10))));

        verify(chunks, never()).listActiveBySpaceAndIds(anyLong(), anyList());
    }
}
