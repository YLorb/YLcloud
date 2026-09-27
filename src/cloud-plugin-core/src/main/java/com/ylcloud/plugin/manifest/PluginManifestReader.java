package com.ylcloud.plugin.manifest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.plugin.spi.PluginSpi;
import com.ylcloud.plugin.spi.document.DocumentParseOptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 将有限大小的 JSON 读成经过校验的说明书。拒绝未知/重复字段、类型强制转换和尾随 JSON。
 * 只读数据，不访问 Registry、不加载类、不拉取镜像、不启动程序；输入流由调用者关闭。
 */
public final class PluginManifestReader {
    public static final int MAX_BYTES = 64 * 1024;
    private final ObjectMapper mapper;

    public PluginManifestReader() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(24).maxStringLength(8192).maxNumberLength(10).build())
                .build();
        mapper = new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public PluginManifest read(InputStream input) {
        Objects.requireNonNull(input, "input");
        byte[] bytes;
        try {
            // 多读一个字节用于区分“刚好达到限制”和“确实超限”，不读取整个任意大小文件。
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (IOException ex) {
            throw new PluginManifestException(PluginManifestException.Code.READ_FAILED,
                    "cannot read plugin manifest", ex);
        }
        if (bytes.length > MAX_BYTES) {
            throw new PluginManifestException(PluginManifestException.Code.TOO_LARGE,
                    "plugin manifest exceeds 64 KiB");
        }
        JsonNode root;
        try {
            root = mapper.readTree(bytes);
        } catch (IOException ex) {
            throw new PluginManifestException(PluginManifestException.Code.INVALID_JSON,
                    "plugin manifest is not valid bounded JSON", ex);
        }
        try {
            return parse(root);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new PluginManifestException(PluginManifestException.Code.INVALID_MANIFEST,
                    "plugin manifest contains invalid or inconsistent declarations", ex);
        }
    }

    private PluginManifest parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw invalid("$", "expected an object");
        }
        // 先判断格式版本，避免把新版字段误报为 V1 的拼写错误。
        int schemaVersion = integer(root, "schemaVersion", "$");
        if (schemaVersion != PluginManifest.SCHEMA_VERSION) {
            throw new PluginManifestException(PluginManifestException.Code.UNSUPPORTED_SCHEMA_VERSION,
                    "unsupported plugin manifest schema version");
        }
        object(root, "$", Set.of("schemaVersion", "id", "name", "version", "description", "spiVersion", "runtimeModes", "providers"));
        int spiVersion = integer(root, "spiVersion", "$");
        if (spiVersion != PluginSpi.VERSION) {
            throw new PluginManifestException(PluginManifestException.Code.INCOMPATIBLE_SPI_VERSION,
                    "plugin SPI version is incompatible with this host");
        }
        Set<PluginManifest.RuntimeMode> modes = new HashSet<>();
        for (String mode : strings(root, "runtimeModes", "$", 1, 2)) {
            modes.add(switch (mode) {
                case "DOCKER" -> PluginManifest.RuntimeMode.DOCKER;
                case "LOCAL" -> PluginManifest.RuntimeMode.LOCAL;
                default -> throw invalid("$.runtimeModes", "unsupported runtime mode");
            });
        }
        JsonNode providerNodes = array(root, "providers", "$", 1, 64);
        List<ProviderManifest> providers = new ArrayList<>();
        for (int i = 0; i < providerNodes.size(); i++) {
            providers.add(provider(providerNodes.get(i), "$.providers[" + i + "]"));
        }
        return new PluginManifest(schemaVersion, text(root, "id", "$"), text(root, "name", "$"),
                text(root, "version", "$"), root.has("description") ? text(root, "description", "$") : "",
                spiVersion, modes, providers);
    }

    private ProviderManifest provider(JsonNode node, String path) {
        object(node, path, Set.of("id", "version", "capability", "documentParsing"));
        if (!text(node, "capability", path).equals("document-parser")) {
            throw invalid(path + ".capability", "unsupported capability");
        }
        String docPath = path + ".documentParsing";
        JsonNode doc = required(node, "documentParsing", path);
        object(doc, docPath, Set.of("mediaTypes", "languages", "supportsTables", "supportsFormulas", "defaultOptions"));
        String defaultsPath = docPath + ".defaultOptions";
        JsonNode defaults = required(doc, "defaultOptions", docPath);
        object(defaults, defaultsPath, Set.of("language", "recognizeTables", "recognizeFormulas"));
        // language 是唯一允许显式 null 的字段；两个默认开关必须给出确定值。
        JsonNode language = required(defaults, "language", defaultsPath);
        String languageValue = language.isNull() ? null : text(defaults, "language", defaultsPath);
        DocumentParseOptions options = new DocumentParseOptions(languageValue,
                bool(defaults, "recognizeTables", defaultsPath), bool(defaults, "recognizeFormulas", defaultsPath));
        if (languageValue != null && !languageValue.equals(options.language())) {
            throw invalid(defaultsPath + ".language", "language tag must be canonical");
        }
        DocumentParserDeclaration declaration = new DocumentParserDeclaration(
                strings(doc, "mediaTypes", docPath, 1, 64), strings(doc, "languages", docPath, 0, 64),
                bool(doc, "supportsTables", docPath), bool(doc, "supportsFormulas", docPath), options);
        return new ProviderManifest(text(node, "id", path), text(node, "version", path),
                ProviderManifest.Capability.DOCUMENT_PARSER, declaration);
    }

    private static void object(JsonNode node, String path, Set<String> fields) {
        if (!node.isObject()) {
            throw invalid(path, "expected an object");
        }
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!fields.contains(names.next())) {
                // 不在错误中回显未知键或字段值，避免将输入中的凭证带入对外错误。
                throw invalid(path, "unknown field");
            }
        }
    }

    private static JsonNode required(JsonNode object, String field, String path) {
        JsonNode value = object.get(field);
        if (value == null) {
            throw invalid(path + "." + field, "required field is missing");
        }
        return value;
    }

    private static String text(JsonNode object, String field, String path) {
        JsonNode value = required(object, field, path);
        if (!value.isTextual()) {
            throw invalid(path + "." + field, "expected a string");
        }
        return value.textValue();
    }

    private static int integer(JsonNode object, String field, String path) {
        JsonNode value = required(object, field, path);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(path + "." + field, "expected a 32-bit integer token");
        }
        return value.intValue();
    }

    private static boolean bool(JsonNode object, String field, String path) {
        JsonNode value = required(object, field, path);
        if (!value.isBoolean()) {
            throw invalid(path + "." + field, "expected a boolean");
        }
        return value.booleanValue();
    }

    private static JsonNode array(JsonNode object, String field, String path, int min, int max) {
        JsonNode value = required(object, field, path);
        if (!value.isArray() || value.size() < min || value.size() > max) {
            throw invalid(path + "." + field, "invalid array type or size");
        }
        return value;
    }

    private static Set<String> strings(JsonNode object, String field, String path, int min, int max) {
        Set<String> values = new HashSet<>();
        for (JsonNode value : array(object, field, path, min, max)) {
            if (!value.isTextual() || !values.add(value.textValue())) {
                throw invalid(path + "." + field, "expected unique string values");
            }
        }
        return Set.copyOf(values);
    }

    private static PluginManifestException invalid(String path, String reason) {
        return new PluginManifestException(PluginManifestException.Code.INVALID_MANIFEST, path + ": " + reason);
    }
}
