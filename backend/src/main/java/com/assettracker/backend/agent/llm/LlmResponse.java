package com.assettracker.backend.agent.llm;

import java.util.List;

/**
 * One model response. Either the model wants to run tools ({@code TOOL_USE}, with a
 * non-empty {@code toolCalls}) or it produced its final answer ({@code END}, with
 * {@code text} holding the {@code ExecutionPlan} JSON).
 */
public record LlmResponse(
    StopReason stopReason,
    String text,
    List<ToolCall> toolCalls
) {
    public enum StopReason { TOOL_USE, END }

    public static LlmResponse toolUse(String text, List<ToolCall> toolCalls) {
        return new LlmResponse(StopReason.TOOL_USE, text, toolCalls);
    }

    public static LlmResponse end(String text) {
        return new LlmResponse(StopReason.END, text, List.of());
    }
}
