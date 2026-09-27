package com.ylcloud.pluginapi;

import com.ylcloud.plugin.manifest.PluginManifest;
import com.ylcloud.plugin.runtime.*;
import com.ylcloud.plugin.spi.ProviderDescriptor;
import java.util.Objects;

/** 仅由可信服务端代码声明为 Bean；客户端不能上传工厂、断言或启动命令。 */
public record PluginBackendBinding(String pluginId, String version, PluginManifest.RuntimeMode mode,
                                   PluginRuntimeFactory factory, PluginRuntime.Limits limits, PluginHealthPlan healthPlan) {
    public PluginBackendBinding {
        new ProviderDescriptor(pluginId, version);
        Objects.requireNonNull(mode); Objects.requireNonNull(factory);
        Objects.requireNonNull(limits); Objects.requireNonNull(healthPlan);
    }
}
