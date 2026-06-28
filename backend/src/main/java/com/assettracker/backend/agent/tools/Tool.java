package com.assettracker.backend.agent.tools;

import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single read-only capability the LLM may invoke during planning.
 *
 * <p>The {@code description} is API: the model selects tools by name + description, so
 * write it like product copy, not Javadoc. {@code inputSchema} is a JSON Schema object
 * (the shape handed to the provider as {@code input_schema}); {@code invoke} runs the
 * underlying {@code GraphService} read and returns its result as JSON.
 *
 * <p>By construction every tool is a <b>read</b>. The {@code agent} package never imports
 * {@code GraphWriter}, so there is no lexical path from a tool to a mutation.
 */
public record Tool(
    String name,
    String description,
    JsonNode inputSchema,
    Function<JsonNode, JsonNode> invoke
) {}
