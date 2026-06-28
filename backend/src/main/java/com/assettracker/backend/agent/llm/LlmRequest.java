package com.assettracker.backend.agent.llm;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single round-trip request to the model: the system prompt, the running message
 * history, the available {@code tools} (provider tool-spec array from
 * {@code ToolRegistry.toolSpecs()}), and a token budget.
 */
public record LlmRequest(
    String system,
    List<LlmMessage> messages,
    JsonNode tools,
    int maxTokens
) {}
