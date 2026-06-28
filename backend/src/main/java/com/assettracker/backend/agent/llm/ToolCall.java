package com.assettracker.backend.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A request from the model to run one tool. {@code id} correlates this call with the
 * {@link ToolResult} we send back. {@code input} is the JSON arguments object.
 */
public record ToolCall(
    String id,
    String name,
    JsonNode input
) {}
