package com.ylcloud.config;

import com.ylcloud.utils.ShortLinkUtil;
import com.ylcloud.service.ShortLinkNodeLease;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShortLinkConfiguration {
    @Bean
    public ShortLinkUtil shortLinkUtil(@Value("${ylcloud.short-link.node-id:0}") long nodeId,
                                      ShortLinkNodeLease lease, MeterRegistry metrics) {
        return new ShortLinkUtil(nodeId, lease::requireActive,
                event -> metrics.counter("short.link.generation", "event", event).increment());
    }
}
