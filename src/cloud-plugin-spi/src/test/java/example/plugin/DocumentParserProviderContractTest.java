package example.plugin;

import com.ylcloud.plugin.spi.ProviderCallContext;
import com.ylcloud.plugin.spi.ProviderException;
import com.ylcloud.plugin.spi.document.DocumentContent;
import com.ylcloud.plugin.spi.document.DocumentParseRequest;
import com.ylcloud.plugin.spi.document.DocumentParseOptions;
import com.ylcloud.plugin.spi.document.DocumentParserProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserProviderContractTest {
    private final DocumentParserProvider provider = new PlainTextProvider();

    @Test
    void externalAuthorImplementsOnlySpiAndCallerReceivesStructuredText() throws Exception {
        var content = new TrackedContent("合同内容");
        var result = provider.parse(request(content, " Text/Plain ", 1024), active());
        assertEquals("example.plain-text", provider.descriptor().id());
        assertEquals("合同内容", result.fullText());
        assertEquals(1, result.blocks().get(0).pageNo());
        assertNull(result.blocks().get(0).confidence());
        assertEquals(1, content.opened.get());
        assertEquals(1, content.closed.get());
        assertThrows(UnsupportedOperationException.class, () -> provider.supportedMediaTypes().add("image/png"));
    }

    @Test
    void snapshotCanBeReopenedWithoutReusingAnExhaustedStream() throws Exception {
        var content = new TrackedContent("same snapshot");
        assertEquals(provider.parse(request(content, "text/plain", 1024), active()),
                provider.parse(request(content, "text/plain", 1024), active()));
        assertEquals(2, content.opened.get());
        assertEquals(2, content.closed.get());
    }

    @Test
    void unsupportedFormatDoesNotOpenContent() {
        var content = new TrackedContent("data");
        var ex = assertThrows(ProviderException.class,
                () -> provider.parse(request(content, "application/pdf", 1024), active()));
        assertEquals(ProviderException.Code.UNSUPPORTED_MEDIA_TYPE, ex.code());
        assertEquals(0, content.opened.get());
    }

    @Test
    void expiredCallDoesNotOpenContent() {
        var content = new TrackedContent("data");
        var ex = assertThrows(ProviderException.class, () -> provider.parse(request(content, "text/plain", 1024),
                new ProviderCallContext("expired", Instant.EPOCH)));
        assertEquals(ProviderException.Code.DEADLINE_EXCEEDED, ex.code());
        assertEquals(0, content.opened.get());
    }

    @Test
    void interruptionIsSeparateFromFailureAndFlagIsPreserved() {
        var content = new TrackedContent("data");
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class,
                    () -> provider.parse(request(content, "text/plain", 1024), active()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, content.opened.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionDuringReadClosesStreamBeforePropagating() {
        var closed = new AtomicInteger();
        DocumentContent content = () -> new ByteArrayInputStream(new byte[] {65}) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                Thread.currentThread().interrupt();
                return super.read(bytes, offset, length);
            }
            @Override
            public void close() throws IOException {
                closed.incrementAndGet();
                super.close();
            }
        };
        try {
            assertThrows(InterruptedException.class,
                    () -> provider.parse(request(content, "text/plain", 1024), active()));
            assertEquals(1, closed.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void byteLimitFailsInsteadOfReturningTruncatedSuccessAndClosesStream() {
        var content = new TrackedContent("four");
        var ex = assertThrows(ProviderException.class,
                () -> provider.parse(request(content, "text/plain", 3), active()));
        assertEquals(ProviderException.Code.LIMIT_EXCEEDED, ex.code());
        assertEquals(1, content.closed.get());
    }

    @Test
    void byteLimitIsInclusive() throws Exception {
        var result = provider.parse(request(new TrackedContent("four"), "text/plain", 4), active());
        assertEquals("four", result.fullText());
    }

    @Test
    void emptyDocumentRemainsEmptyRatherThanBecomingMetadataFallback() throws Exception {
        var result = provider.parse(request(new TrackedContent(""), "text/plain", 1), active());
        assertEquals("", result.fullText());
        assertTrue(result.blocks().isEmpty());
    }

    @Test
    void inputFailureRetainsCauseButUsesSafeMessageAndClosesStream() {
        var closed = new AtomicInteger();
        var cause = new IOException("private storage diagnostic");
        DocumentContent content = () -> new InputStream() {
            @Override
            public int read() throws IOException { throw cause; }
            @Override
            public void close() { closed.incrementAndGet(); }
        };
        var ex = assertThrows(ProviderException.class,
                () -> provider.parse(request(content, "text/plain", 1024), active()));
        assertEquals(ProviderException.Code.INPUT_READ_FAILED, ex.code());
        assertSame(cause, ex.getCause());
        assertEquals("document input failed", ex.getMessage());
        assertEquals(1, closed.get());
    }

    @Test
    void openFailureIsClassified() {
        DocumentContent content = () -> { throw new IOException("cannot open"); };
        var ex = assertThrows(ProviderException.class,
                () -> provider.parse(request(content, "text/plain", 1024), active()));
        assertEquals(ProviderException.Code.INPUT_READ_FAILED, ex.code());
    }

    @Test
    void invalidUtf8IsNotSilentlyReplaced() {
        DocumentContent content = () -> new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28});
        var ex = assertThrows(ProviderException.class,
                () -> provider.parse(request(content, "text/plain", 1024), active()));
        assertEquals(ProviderException.Code.INVALID_DOCUMENT, ex.code());
    }

    @Test
    void sameProviderAcceptsConcurrentRequestsWithoutMixingResults() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(2);
        try {
            var first = executor.submit(() -> provider.parse(request(overlappingContent("alpha", entered), "text/plain", 10), active()));
            var second = executor.submit(() -> provider.parse(request(overlappingContent("beta", entered), "text/plain", 10), active()));
            assertEquals("alpha", first.get(5, TimeUnit.SECONDS).fullText());
            assertEquals("beta", second.get(5, TimeUnit.SECONDS).fullText());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void explicitUnsupportedOptionsAreRejectedBeforeReadingContent() {
        var overrides = java.util.List.of(
                new DocumentParseOptions("en", null, null),
                new DocumentParseOptions(null, true, null),
                new DocumentParseOptions(null, null, true));
        for (var options : overrides) {
            var content = new TrackedContent("data");
            var request = new DocumentParseRequest("snapshot", "sample.txt", "text/plain", content, 10, 1, options);
            var ex = assertThrows(ProviderException.class, () -> provider.parse(request, active()));
            assertEquals(ProviderException.Code.UNSUPPORTED_OPTION, ex.code());
            assertEquals(0, content.opened.get());
        }
    }

    @Test
    void explicitDisableIsHonoredByProviderWithoutRecognitionFeatures() throws Exception {
        var request = new DocumentParseRequest("snapshot", "sample.txt", "text/plain",
                new TrackedContent("plain"), 10, 1, new DocumentParseOptions(null, false, false));
        assertEquals("plain", provider.parse(request, active()).fullText());
    }

    private static DocumentContent overlappingContent(String text, CountDownLatch entered) {
        return () -> {
            entered.countDown();
            try {
                if (!entered.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("provider calls did not overlap");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("test interrupted", ex);
            }
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        };
    }

    private static ProviderCallContext active() {
        return new ProviderCallContext("test-call", Instant.now().plusSeconds(30));
    }

    private static DocumentParseRequest request(DocumentContent content, String mediaType, long limit) {
        return new DocumentParseRequest("snapshot-1", "sample.txt", mediaType, content, limit, 1);
    }

    private static final class TrackedContent implements DocumentContent {
        private final byte[] bytes;
        private final AtomicInteger opened = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();

        private TrackedContent(String text) {
            bytes = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public InputStream openStream() {
            opened.incrementAndGet();
            return new ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    closed.incrementAndGet();
                    super.close();
                }
            };
        }
    }
}
