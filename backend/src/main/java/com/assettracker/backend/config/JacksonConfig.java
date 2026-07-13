package com.assettracker.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson 2 mapper for agent/planner code. Boot 4 uses Jackson 3 for HTTP,
 * so we wire up our own. Controllers return pre-serialized strings to keep the two apart.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper agentObjectMapper() {
        return new ObjectMapper();
    }
}
