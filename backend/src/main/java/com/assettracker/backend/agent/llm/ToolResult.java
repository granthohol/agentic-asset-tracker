package com.assettracker.backend.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The result of executing a {@link ToolCall} locally, fed back to the model.
 * {@code toolCallId} matches {@link ToolCall#id()}. {@code isError} flags that the tool
 * threw (e.g. bad arguments) so the model can correct itself on the next turn.
 */
public record ToolResult(
    String toolCallId,
    String toolName,
    JsonNode content,
    boolean isError
) {}
