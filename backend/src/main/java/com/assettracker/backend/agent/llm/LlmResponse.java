package com.assettracker.backend.agent.llm;

import java.util.List;

/** Model reply: wants tools (TOOL_USE) or final text (END, usually ExecutionPlan JSON). */
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
