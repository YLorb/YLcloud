package com.ylcloud.pluginapi;

import com.ylcloud.plugin.manager.PluginManager;
import com.ylcloud.plugin.registry.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class PluginApiConfigurationTest {
    @Test void configurationStartsWithoutAnyRuntimeBackend() {
        new ApplicationContextRunner().withUserConfiguration(PluginApiConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(PluginManager.class).hasSingleBean(ProviderRegistry.class)
                    .hasSingleBean(PluginApiService.class);
            assertThat(context.getBean(PluginApiService.class).list()).isEmpty();
        });
    }
}
