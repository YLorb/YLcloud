package example.plugin;

import com.ylcloud.plugin.spi.ProviderCallContext;
import com.ylcloud.plugin.spi.ProviderDescriptor;
import com.ylcloud.plugin.spi.ProviderException;
import com.ylcloud.plugin.spi.document.DocumentBlock;
import com.ylcloud.plugin.spi.document.DocumentParseRequest;
import com.ylcloud.plugin.spi.document.DocumentParseResult;
import com.ylcloud.plugin.spi.document.DocumentParserProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Test-only author example. No OCR, transport, registry or runtime implementation. */
final class PlainTextProvider implements DocumentParserProvider {
    private static final ProviderDescriptor DESCRIPTOR = new ProviderDescriptor("example.plain-text", "1.0.0");
    private static final Set<String> MEDIA_TYPES = Set.of("text/plain");

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return MEDIA_TYPES;
    }

    @Override
    public DocumentParseResult parse(DocumentParseRequest request, ProviderCallContext context)
            throws ProviderException, InterruptedException {
        context.checkActive();
        if (!MEDIA_TYPES.contains(request.mediaType())) {
            throw new ProviderException(ProviderException.Code.UNSUPPORTED_MEDIA_TYPE, "unsupported document format");
        }
        var options = request.options();
        if (options.language() != null || Boolean.TRUE.equals(options.recognizeTables())
                || Boolean.TRUE.equals(options.recognizeFormulas())) {
            throw new ProviderException(ProviderException.Code.UNSUPPORTED_OPTION, "unsupported parse option");
        }
        // False flags are honored: this example performs no table/formula recognition.
        // This example treats one plain-text document as one logical page.
        try (var input = request.content().openStream()) {
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            long total = 0;
            while (true) {
                context.checkActive();
                int count = input.read(buffer);
                if (count == -1) {
                    break;
                }
                if (count > request.maxInputBytes() - total) {
                    throw new ProviderException(ProviderException.Code.LIMIT_EXCEEDED, "document byte limit exceeded");
                }
                output.write(buffer, 0, count);
                total += count;
            }
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
            context.checkActive();
            return new DocumentParseResult(text,
                    text.isEmpty() ? List.of() : List.of(new DocumentBlock(0, DocumentBlock.Type.TEXT,
                            text, 1, null, List.of(), null, null)), List.of());
        } catch (CharacterCodingException ex) {
            throw new ProviderException(ProviderException.Code.INVALID_DOCUMENT, "document is not valid UTF-8", ex);
        } catch (IOException ex) {
            throw new ProviderException(ProviderException.Code.INPUT_READ_FAILED, "document input failed", ex);
        }
    }
}
