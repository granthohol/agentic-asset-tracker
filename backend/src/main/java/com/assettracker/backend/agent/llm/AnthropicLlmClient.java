package com.assettracker.backend.agent.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Real LlmClient via Anthropic Messages API. Active when llm.provider=anthropic. */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic")
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final String anthropicVersion;
    /** disabled (default) or adaptive. Sonnet 5 bills thinking as output; pricey for planning. */
    private final String thinkingMode;
    /** Effort when thinking=adaptive: low|medium|high|xhigh|max. */
    private final String effort;
    /** Cache system prompt + tool specs to cut repeat input cost. */
    private final boolean promptCache;

    public AnthropicLlmClient(
        ObjectMapper mapper,
        @Value("${anthropic.api-key:}") String apiKey,
        @Value("${anthropic.model:claude-haiku-4-5}") String model,
        @Value("${anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
        @Value("${anthropic.version:2023-06-01}") String anthropicVersion,
        @Value("${anthropic.thinking:disabled}") String thinkingMode,
        @Value("${anthropic.effort:low}") String effort,
        @Value("${anthropic.prompt-cache:true}") boolean promptCache
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            // Missing API key should fail at startup, not on first prompt.
            throw new IllegalStateException(
                "llm.provider=anthropic but anthropic.api-key is blank. Set ANTHROPIC_API_KEY "
                    + "(or anthropic.api-key), or use llm.provider=stub for offline mode.");
        }
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.anthropicVersion = anthropicVersion;
        this.thinkingMode = thinkingMode;
        this.effort = effort;
        this.promptCache = promptCache;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        log.info("AnthropicLlmClient active (model={}, thinking={}, promptCache={})",
            model, thinkingMode, promptCache);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String body = buildRequestBody(request);

        HttpResponse<String> response;
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(60))
                .header("x-api-key", apiKey)
                .header("anthropic-version", anthropicVersion)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Anthropic request failed: " + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                "Anthropic API returned HTTP " + response.statusCode() + ": " + response.body());
        }

        return parseResponse(response.body());
    }

    String buildRequestBody(LlmRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", request.maxTokens());

        // Thinking is the main cost knob on Sonnet 5. Off by default for planning.
        ObjectNode thinking = mapper.createObjectNode();
        if ("adaptive".equalsIgnoreCase(thinkingMode)) {
            thinking.put("type", "adaptive");
            root.set("thinking", thinking);
            ObjectNode outputConfig = mapper.createObjectNode();
            outputConfig.put("effort", effort);
            root.set("output_config", outputConfig);
        } else {
            thinking.put("type", "disabled");
            root.set("thinking", thinking);
        }

        // Tools are identical every turn; forward as-is.
        if (request.tools() != null && !request.tools().isNull()) {
            root.set("tools", request.tools());
        }

        if (request.system() != null && !request.system().isBlank()) {
            if (promptCache) {
                // Cache the big unchanging system prompt across multi-turn loops.
                ArrayNode systemBlocks = mapper.createArrayNode();
                ObjectNode block = systemBlocks.addObject();
                block.put("type", "text");
                block.put("text", request.system());
                block.putObject("cache_control").put("type", "ephemeral");
                root.set("system", systemBlocks);
            } else {
                root.put("system", request.system());
            }
        }

        root.set("messages", buildMessages(request.messages()));
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Anthropic request", e);
        }
    }

    private ArrayNode buildMessages(List<LlmMessage> messages) {
        ArrayNode arr = mapper.createArrayNode();
        if (messages == null) {
            return arr;
        }
        for (LlmMessage message : messages) {
            switch (message.role()) {
                case USER -> arr.add(userMessage(message.text()));
                case ASSISTANT -> arr.add(assistantMessage(message));
                case TOOL -> arr.add(toolResultMessage(message));
            }
        }
        return arr;
    }

    private ObjectNode userMessage(String text) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", text == null ? "" : text);
        return msg;
    }

    private ObjectNode assistantMessage(LlmMessage message) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "assistant");
        ArrayNode content = mapper.createArrayNode();

        if (message.text() != null && !message.text().isBlank()) {
            ObjectNode textBlock = mapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", message.text());
            content.add(textBlock);
        }
        for (ToolCall call : message.toolCalls()) {
            ObjectNode toolUse = mapper.createObjectNode();
            toolUse.put("type", "tool_use");
            toolUse.put("id", call.id());
            toolUse.put("name", call.name());
            toolUse.set("input", call.input() == null ? mapper.createObjectNode() : call.input());
            content.add(toolUse);
        }
        // Anthropic rejects empty content arrays.
        if (content.isEmpty()) {
            ObjectNode textBlock = mapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", message.text() == null ? "" : message.text());
            content.add(textBlock);
        }
        msg.set("content", content);
        return msg;
    }

    /** Tool results go back as a user message of tool_result blocks. */
    private ObjectNode toolResultMessage(LlmMessage message) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ArrayNode content = mapper.createArrayNode();
        for (ToolResult result : message.toolResults()) {
            ObjectNode block = mapper.createObjectNode();
            block.put("type", "tool_result");
            block.put("tool_use_id", result.toolCallId());
            // tool_result content must be text; stringify the JSON.
            block.put("content", stringifyContent(result.content()));
            if (result.isError()) {
                block.put("is_error", true);
            }
            content.add(block);
        }
        msg.set("content", content);
        return msg;
    }

    private String stringifyContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        try {
            return mapper.writeValueAsString(content);
        } catch (Exception e) {
            return String.valueOf(content);
        }
    }

    LlmResponse parseResponse(String bodyJson) {
        JsonNode root;
        try {
            root = mapper.readTree(bodyJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Anthropic response: " + e.getMessage(), e);
        }

        logUsage(root.path("usage"));

        JsonNode content = root.path("content");
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    text.append(block.path("text").asText(""));
                } else if ("tool_use".equals(type)) {
                    JsonNode input = block.get("input");
                    toolCalls.add(new ToolCall(
                        block.path("id").asText(),
                        block.path("name").asText(),
                        input == null ? mapper.createObjectNode() : input
                    ));
                }
            }
        }

        // Prefer checking toolCalls; stop_reason drifts across API versions.
        if (!toolCalls.isEmpty() || "tool_use".equals(root.path("stop_reason").asText())) {
            return LlmResponse.toolUse(text.toString(), toolCalls);
        }
        return LlmResponse.end(text.toString());
    }

    /**
     * Log token usage per turn. cacheWrite/cacheRead both 0 means caching never kicked in
     * (prefix too small; Haiku needs 4096 tokens, Sonnet 1024).
     */
    private void logUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode()) {
            return;
        }
        log.info(
            "Anthropic usage: input={}, output={}, cacheWrite={}, cacheRead={}",
            usage.path("input_tokens").asInt(0),
            usage.path("output_tokens").asInt(0),
            usage.path("cache_creation_input_tokens").asInt(0),
            usage.path("cache_read_input_tokens").asInt(0));
    }
}
