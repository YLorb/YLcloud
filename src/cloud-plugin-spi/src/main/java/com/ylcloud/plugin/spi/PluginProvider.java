package com.ylcloud.plugin.spi;

/**
 * Common identity of a business capability implementation, not a plugin loader.
 * Implementations expose stable, non-null metadata without network or disk I/O.
 * Lifecycle, discovery and deployment belong to the host, outside this SPI.
 */
public interface PluginProvider {
    ProviderDescriptor descriptor();
}
