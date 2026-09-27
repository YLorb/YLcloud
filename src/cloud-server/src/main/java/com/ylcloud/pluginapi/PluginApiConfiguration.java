package com.ylcloud.pluginapi;

import com.ylcloud.plugin.manager.PluginManager;
import com.ylcloud.plugin.registry.ProviderRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PluginApiConfiguration {
    @Bean public ProviderRegistry pluginProviderRegistry() { return new ProviderRegistry(); }
    @Bean public PluginManager pluginManager(ProviderRegistry registry) { return new PluginManager(registry); }
    @Bean(destroyMethod = "shutdown")
    public PluginApiService pluginApiService(PluginManager manager, ObjectProvider<PluginBackendBinding> bindings) {
        return new PluginApiService(manager, bindings.orderedStream().toList());
    }
}
