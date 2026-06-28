package com.assettracker.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Provides a Jackson 2 {@link ObjectMapper} bean for internal agent/tool JSON work.
 *
 * <p>Spring Boot 4 baselines <b>Jackson 3</b> ({@code tools.jackson.*}) for the web layer,
 * so there is no auto-configured {@code com.fasterxml.jackson.databind.ObjectMapper} bean.
 * Our planner code (ToolRegistry, orchestrator, stub client) is written against Jackson 2
 * (which is also on the classpath), so we register a single Jackson 2 mapper here for those
 * injections. The web layer keeps using its own Jackson 3 mapper independently; controllers
 * return pre-serialized JSON strings to avoid mixing node types across the two versions.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper agentObjectMapper() {
        return new ObjectMapper();
    }
}
