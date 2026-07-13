package com.assettracker.backend.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** One tool the model wants to run. id ties back to the ToolResult we return. */
public record ToolCall(
    String id,
    String name,
    JsonNode input
) {}
