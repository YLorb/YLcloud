package com.ylcloud.plugin.spi;

import java.util.Objects;

/**
 * Identity of one provider implementation. The ID is stable across deployments;
 * the version identifies its implementation, not the SPI or model version.
 * Plugin package metadata and configuration belong to the future manifest.
 */
public record ProviderDescriptor(String id, String version) {
    public ProviderDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        if (!id.matches("[a-z][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid provider id");
        }
        if (version.isBlank() || !version.equals(version.strip()) || version.length() > 128) {
            throw new IllegalArgumentException("invalid provider version");
        }
    }
}
