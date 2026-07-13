package com.assettracker.backend.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** Local result for a ToolCall. isError=true if the tool threw so the model can retry. */
public record ToolResult(
    String toolCallId,
    String toolName,
    JsonNode content,
    boolean isError
) {}
