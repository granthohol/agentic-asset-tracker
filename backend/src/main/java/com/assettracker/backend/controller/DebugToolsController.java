package com.assettracker.backend.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assettracker.backend.agent.tools.ToolRegistry;

/**
 * Dev/debug surface for inspecting the read-only tool catalog the orchestrator hands the
 * LLM. This is exactly the {@code tools=[...]} payload (name + description + input_schema)
 * the model sees. Read-only; safe to leave on locally. In a hardened deployment, gate this
 * behind {@code @Profile("dev")}.
 *
 * <p>Returns a pre-serialized JSON string (built with the Jackson 2 agent mapper) so we
 * don't hand Jackson 2 node objects to Spring Boot 4's Jackson 3 web converter.
 */
@RestController
@RequestMapping("/api/debug")
public class DebugToolsController {

    private final ToolRegistry toolRegistry;

    public DebugToolsController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public String tools() {
        return toolRegistry.toolSpecsJson();
    }
}
