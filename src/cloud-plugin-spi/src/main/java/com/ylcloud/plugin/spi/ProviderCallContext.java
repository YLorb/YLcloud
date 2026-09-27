package com.ylcloud.plugin.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * Per-call tracing and deadline. invocationId is correlation, not an idempotency
 * guarantee. The deadline covers the entire call, including any adapter I/O.
 */
public record ProviderCallContext(String invocationId, Instant deadline) {
    public ProviderCallContext {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(deadline, "deadline");
        if (invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId must not be blank");
        }
    }

    /**
     * Cooperative check before work, between bounded steps and before returning.
     * Does not clear the interrupt flag. It cannot interrupt blocked/native work;
     * adapters still need I/O timeouts and the runtime must enforce its own budget.
     */
    public void checkActive() throws ProviderException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("provider call interrupted");
        }
        if (!Instant.now().isBefore(deadline)) {
            throw new ProviderException(ProviderException.Code.DEADLINE_EXCEEDED,
                    "provider call deadline exceeded");
        }
    }
}
