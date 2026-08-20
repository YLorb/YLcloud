package com.ylcloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.Exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpaceFilePreflightServiceTest {
    @Test
    void idempotencyKeyIsStableForAnExactRetry() {
        String first = SpaceFilePreflightService.buildIdempotencyKey("lesson.pdf", "abc123", 42L, 7L);
        String retry = SpaceFilePreflightService.buildIdempotencyKey("lesson.pdf", "abc123", 42L, 7L);

        assertEquals(first, retry);
        assertTrue(first.length() <= 128);
    }

    @Test
    void idempotencyKeyIncludesDeclaredMetadataAndSubject() {
        String baseline = SpaceFilePreflightService.buildIdempotencyKey("lesson.pdf", "abc123", 42L, 7L);

        assertNotEquals(baseline, SpaceFilePreflightService.buildIdempotencyKey("lesson.pptx", "abc123", 42L, 7L));
        assertNotEquals(baseline, SpaceFilePreflightService.buildIdempotencyKey("lesson.pdf", "abc123", 43L, 7L));
        assertNotEquals(baseline, SpaceFilePreflightService.buildIdempotencyKey("lesson.pdf", "abc123", 42L, 8L));
    }

    @Test
    void runtimeFailureIsInfrastructureErrorInsteadOfUnsafeFile() throws Exception {
        BaseException error = assertThrows(BaseException.class, () -> SpaceFilePreflightService.validateResult(
                new ObjectMapper().readTree("{\"status\":\"FAILED\",\"error_code\":\"SANDBOX_TOOL_FAILED\"}")));

        assertEquals(503,error.getStatusCode());
        assertTrue(error.getMessage().contains("执行失败"));
    }

    @Test
    void successfulRiskDetectionRemainsAFileRejection() throws Exception {
        BaseException error = assertThrows(BaseException.class, () -> SpaceFilePreflightService.validateResult(
                new ObjectMapper().readTree("{\"status\":\"SUCCEEDED\",\"result\":{\"safe\":false}}")));

        assertEquals(400,error.getStatusCode());
        assertTrue(error.getMessage().contains("未通过"));
    }
}
