package com.assettracker.backend.agent.llm;

import java.util.List;

/**
 * One turn in the conversation. LlmClient maps these to provider wire format.
 * USER: operator prompt. ASSISTANT: model text and/or tool calls. TOOL: our tool results.
 */
public record LlmMessage(
    Role role,
    String text,
    List<ToolCall> toolCalls,
    List<ToolResult> toolResults
) {
    public enum Role { USER, ASSISTANT, TOOL }

    public static LlmMessage user(String text) {
        return new LlmMessage(Role.USER, text, List.of(), List.of());
    }

    public static LlmMessage assistant(String text, List<ToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, text, toolCalls == null ? List.of() : toolCalls, List.of());
    }

    public static LlmMessage toolResults(List<ToolResult> toolResults) {
        return new LlmMessage(Role.TOOL, null, List.of(), toolResults == null ? List.of() : toolResults);
    }
}
