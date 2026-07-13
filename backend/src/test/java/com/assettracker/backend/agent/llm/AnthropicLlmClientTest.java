package com.assettracker.backend.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure unit tests for the two seams that don't touch the network: turning our
 * provider-agnostic {@link LlmRequest} into the Anthropic Messages request body, and turning
 * a canned Anthropic response body into an {@link LlmResponse}.
 */
class AnthropicLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    // Defaults under test: thinking disabled, prompt cache on.
    private final AnthropicLlmClient client = new AnthropicLlmClient(
        mapper, "test-key", "claude-sonnet-5", "https://example.invalid/v1/messages",
        "2023-06-01", "disabled", "low", true);

    private ArrayNode toolSpecs() {
        ArrayNode tools = mapper.createArrayNode();
        ObjectNode tool = tools.addObject();
        tool.put("name", "list_drones");
        tool.put("description", "List drones.");
        tool.putObject("input_schema").put("type", "object");
        return tools;
    }

    @Test
    void blankApiKeyFailsFast() {
        assertThatThrownBy(() -> new AnthropicLlmClient(
            mapper, "  ", "claude-sonnet-5", "https://example.invalid", "2023-06-01",
            "disabled", "low", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("anthropic.api-key");
    }

    @Test
    void buildsRequestBodyWithSystemToolsAndUserMessage() throws Exception {
        LlmRequest request = new LlmRequest(
            "You are the planner.",
            List.of(LlmMessage.user("scramble 3 drones")),
            toolSpecs(),
            2048);

        JsonNode body = mapper.readTree(client.buildRequestBody(request));

        assertThat(body.path("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(2048);
        assertThat(body.path("tools").get(0).path("name").asText()).isEqualTo("list_drones");

        // Cost controls: thinking disabled by default; no output_config in that mode.
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.has("output_config")).isFalse();

        // Prompt cache on: system is a cacheable content-block array, not a bare string.
        JsonNode system = body.path("system");
        assertThat(system.isArray()).isTrue();
        assertThat(system.get(0).path("text").asText()).isEqualTo("You are the planner.");
        assertThat(system.get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");

        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("scramble 3 drones");
    }

    @Test
    void adaptiveThinkingModeSendsEffortAndPlainSystemWhenCacheOff() throws Exception {
        AnthropicLlmClient adaptive = new AnthropicLlmClient(
            mapper, "test-key", "claude-sonnet-5", "https://example.invalid/v1/messages",
            "2023-06-01", "adaptive", "medium", false);

        LlmRequest request = new LlmRequest(
            "You are the planner.",
            List.of(LlmMessage.user("go")),
            toolSpecs(),
            2048);

        JsonNode body = mapper.readTree(adaptive.buildRequestBody(request));

        assertThat(body.path("thinking").path("type").asText()).isEqualTo("adaptive");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("medium");
        // Cache off: system is a plain string.
        assertThat(body.path("system").isTextual()).isTrue();
        assertThat(body.path("system").asText()).isEqualTo("You are the planner.");
    }

    @Test
    void mapsAssistantToolCallsAndToolResultsToAnthropicBlocks() throws Exception {
        ObjectNode toolInput = mapper.createObjectNode().put("threshold", 30);
        ObjectNode toolOutput = mapper.createObjectNode();
        toolOutput.putArray("drones");

        LlmRequest request = new LlmRequest(
            "sys",
            List.of(
                LlmMessage.user("find low battery drones"),
                LlmMessage.assistant("checking", List.of(
                    new ToolCall("call_1", "get_low_battery_drones", toolInput))),
                LlmMessage.toolResults(List.of(
                    new ToolResult("call_1", "get_low_battery_drones", toolOutput, false)))),
            toolSpecs(),
            2048);

        JsonNode messages = mapper.readTree(client.buildRequestBody(request)).path("messages");
        assertThat(messages).hasSize(3);

        // Assistant: a text block followed by a tool_use block.
        JsonNode assistant = messages.get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        JsonNode assistantContent = assistant.path("content");
        assertThat(assistantContent.get(0).path("type").asText()).isEqualTo("text");
        assertThat(assistantContent.get(1).path("type").asText()).isEqualTo("tool_use");
        assertThat(assistantContent.get(1).path("id").asText()).isEqualTo("call_1");
        assertThat(assistantContent.get(1).path("name").asText()).isEqualTo("get_low_battery_drones");
        assertThat(assistantContent.get(1).path("input").path("threshold").asInt()).isEqualTo(30);

        // Tool result: user-role message carrying a tool_result block with stringified JSON.
        JsonNode toolMsg = messages.get(2);
        assertThat(toolMsg.path("role").asText()).isEqualTo("user");
        JsonNode resultBlock = toolMsg.path("content").get(0);
        assertThat(resultBlock.path("type").asText()).isEqualTo("tool_result");
        assertThat(resultBlock.path("tool_use_id").asText()).isEqualTo("call_1");
        assertThat(resultBlock.path("content").asText()).contains("drones");
        assertThat(resultBlock.has("is_error")).isFalse();
    }

    @Test
    void toolResultErrorFlagIsPropagated() throws Exception {
        ObjectNode err = mapper.createObjectNode().put("error", "bad args");
        LlmRequest request = new LlmRequest(
            "sys",
            List.of(LlmMessage.toolResults(List.of(
                new ToolResult("call_9", "get_drone_by_id", err, true)))),
            toolSpecs(),
            2048);

        JsonNode resultBlock = mapper.readTree(client.buildRequestBody(request))
            .path("messages").get(0).path("content").get(0);
        assertThat(resultBlock.path("is_error").asBoolean()).isTrue();
    }

    @Test
    void parsesToolUseResponse() {
        String json = """
            {
              "stop_reason": "tool_use",
              "content": [
                { "type": "text", "text": "Let me inspect the fleet." },
                { "type": "tool_use", "id": "toolu_1", "name": "list_drones", "input": {} }
              ]
            }
            """;

        LlmResponse response = client.parseResponse(json);

        assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.TOOL_USE);
        assertThat(response.text()).isEqualTo("Let me inspect the fleet.");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).id()).isEqualTo("toolu_1");
        assertThat(response.toolCalls().get(0).name()).isEqualTo("list_drones");
    }

    @Test
    void parsesFinalTextResponseAsEnd() {
        String json = """
            {
              "stop_reason": "end_turn",
              "content": [
                { "type": "text", "text": "{\\"planId\\":\\"plan-1\\",\\"rationale\\":\\"go\\",\\"actions\\":[]}" }
              ]
            }
            """;

        LlmResponse response = client.parseResponse(json);

        assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.END);
        assertThat(response.text()).contains("\"planId\":\"plan-1\"");
        assertThat(response.toolCalls()).isEmpty();
    }
}
