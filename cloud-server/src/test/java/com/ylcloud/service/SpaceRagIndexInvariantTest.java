package com.ylcloud.service;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.entity.FileRagChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpaceRagIndexInvariantTest {

    @Test
    void rejectsEmptyAndBlankChunksBeforeIndexSuccess() {
        assertThrows(BaseException.class,() -> SpaceRagService.validateIndexableChunks(List.of()));

        FileRagChunk blank = new FileRagChunk();
        blank.setId(1L);
        blank.setContent("   ");
        assertThrows(BaseException.class,() -> SpaceRagService.validateIndexableChunks(List.of(blank)));
    }

    @Test
    void acceptsPersistedNonEmptyChunks() {
        FileRagChunk chunk = new FileRagChunk();
        chunk.setId(1L);
        chunk.setContent("有效正文");

        assertDoesNotThrow(() -> SpaceRagService.validateIndexableChunks(List.of(chunk)));
    }
}
