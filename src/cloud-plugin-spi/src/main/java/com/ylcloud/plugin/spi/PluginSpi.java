package com.ylcloud.plugin.spi;

/** 宿主与插件共同约定的 SPI 协议代号；不同于 Maven artifact 版本或 Provider 实现版本。 */
public final class PluginSpi {
    public static final int VERSION = 1;
    private PluginSpi() {}
}
