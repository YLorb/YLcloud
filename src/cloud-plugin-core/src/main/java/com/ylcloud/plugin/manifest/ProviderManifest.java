package com.ylcloud.plugin.manifest;

import com.ylcloud.plugin.spi.ProviderDescriptor;

import java.util.Objects;

/** 一个包内的一条 Provider 声明。ID 与实现版本使用 Phase 1 的相同规则。 */
public record ProviderManifest(String id, String version, Capability capability,
                               DocumentParserDeclaration documentParsing) {
    public ProviderManifest {
        new ProviderDescriptor(id, version);
        Objects.requireNonNull(capability, "capability");
        // V1 只理解文档解析能力；新增能力时要显式扩展声明和校验，不能加载任意类名。
        Objects.requireNonNull(documentParsing, "documentParsing");
    }

    public enum Capability { DOCUMENT_PARSER }
}
