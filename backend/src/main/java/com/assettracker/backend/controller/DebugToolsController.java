package com.assettracker.backend.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.assettracker.backend.agent.tools.ToolRegistry;

/**
 * Peek at the tool catalog the LLM sees. Fine for local dev; add @Profile("dev") in prod.
 * Returns a pre-serialized string to avoid Jackson 2/3 mixing in the web layer.
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
