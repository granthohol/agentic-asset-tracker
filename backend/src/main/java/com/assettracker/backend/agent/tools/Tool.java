package com.assettracker.backend.agent.tools;

import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One read-only tool the LLM can call during planning.
 * description is what the model sees when picking tools.
 * The agent package never imports GraphWriter, so tools can't write.
 */
public record Tool(
    String name,
    String description,
    JsonNode inputSchema,
    Function<JsonNode, JsonNode> invoke
) {}
