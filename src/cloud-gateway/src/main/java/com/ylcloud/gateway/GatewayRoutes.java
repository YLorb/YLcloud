package com.ylcloud.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutes {
    @Bean
    RouteLocator publicRoutes(RouteLocatorBuilder builder, GatewaySettings settings) {
        // Existing public contract only. Internal service APIs and management endpoints have no route.
        return builder.routes().route("cloud-api", r -> r.path("/api/**")
                .filters(f -> f.preserveHostHeader()).uri(settings.upstream())).build();
    }
}
