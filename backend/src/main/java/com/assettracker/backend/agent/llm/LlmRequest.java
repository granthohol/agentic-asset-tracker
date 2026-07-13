package com.assettracker.backend.agent.llm;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** One round-trip: system prompt, message history, tool specs, token budget. */
public record LlmRequest(
    String system,
    List<LlmMessage> messages,
    JsonNode tools,
    int maxTokens
) {}
