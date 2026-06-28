package com.assettracker.backend.agent.llm;

import java.util.List;

/**
 * One turn in the conversation, provider-agnostic. A concrete {@link LlmClient} maps these
 * to its own wire format (Anthropic blocks, OpenAI messages, etc.).
 *
 * <ul>
 *   <li>{@code USER} — operator prompt (or a correction we inject).</li>
 *   <li>{@code ASSISTANT} — model output: free {@code text} and/or {@code toolCalls}.</li>
 *   <li>{@code TOOL} — our local execution results ({@code toolResults}) for the prior
 *       assistant's tool calls.</li>
 * </ul>
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
