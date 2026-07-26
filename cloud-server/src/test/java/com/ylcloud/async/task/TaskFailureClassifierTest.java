package com.ylcloud.async.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskFailureClassifierTest {
    private final TaskErrorSanitizer sanitizer = new TaskErrorSanitizer();
    private final TaskFailureClassifier classifier = new TaskFailureClassifier(sanitizer);

    @Test
    void classifiesRetryableFatalStaleAndUnknown() {
        assertTrue(classifier.classify(new RetryableTaskException("temporary")).retryable());
        assertFalse(classifier.classify(new FatalTaskException("bad input")).retryable());
        assertTrue(classifier.classify(new StaleTaskException("old")).stale());
        assertTrue(classifier.classify(new IllegalStateException("unknown")).retryable());
    }

    @Test
    void redactsSecretsAndTruncatesWithoutPersistingStackTrace() {
        String safe = sanitizer.sanitize("Bearer abc.def token=secret password=hunter2\n" + "x".repeat(1200));
        assertFalse(safe.contains("abc.def"));
        assertFalse(safe.contains("hunter2"));
        assertTrue(safe.contains("[REDACTED]"));
        assertEquals(1000,safe.length());
        assertFalse(safe.contains("\n"));
    }
}
