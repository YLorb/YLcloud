package com.ylcloud.plugin.manifest;

import com.ylcloud.plugin.spi.PluginSpi;
import com.ylcloud.plugin.spi.ProviderDescriptor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 插件包的不可变说明书；只描述静态信息，不保存 enabled、health 等运行状态。
 * schemaVersion 是 JSON 结构版本，spiVersion 是调用契约版本，version 是插件包版本。
 */
public record PluginManifest(int schemaVersion, String id, String name, String version,
                             String description, int spiVersion, Set<RuntimeMode> runtimeModes,
                             List<ProviderManifest> providers) {
    public static final int SCHEMA_VERSION = 1;

    public PluginManifest {
        if (schemaVersion != SCHEMA_VERSION || spiVersion != PluginSpi.VERSION) {
            throw new IllegalArgumentException("unsupported manifest or SPI version");
        }
        new ProviderDescriptor(id, version);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        if (name.isBlank() || !name.equals(name.strip()) || name.length() > 128 || description.length() > 4096) {
            throw new IllegalArgumentException("invalid plugin display metadata");
        }
        runtimeModes = Set.copyOf(runtimeModes);
        providers = List.copyOf(providers);
        if (runtimeModes.isEmpty() || providers.isEmpty() || providers.size() > 64) {
            throw new IllegalArgumentException("runtime modes and providers must be declared");
        }
        Set<String> ids = new HashSet<>();
        for (ProviderManifest provider : providers) {
            if (!ids.add(provider.id())) {
                throw new IllegalArgumentException("duplicate provider id within plugin package");
            }
        }
    }

    /** 部署方式声明，不包含执行指令。LOCAL 的 Python 承载方式留到 Runtime 阶段决定。 */
    public enum RuntimeMode { DOCKER, LOCAL }
}
