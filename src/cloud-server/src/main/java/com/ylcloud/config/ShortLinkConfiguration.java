package com.ylcloud.config;

import com.ylcloud.utils.ShortLinkUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShortLinkConfiguration {
    @Bean
    public ShortLinkUtil shortLinkUtil(@Value("${ylcloud.short-link.node-id:0}") long nodeId) {
        return new ShortLinkUtil(nodeId);
    }
}
