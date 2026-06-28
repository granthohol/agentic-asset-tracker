package com.assettracker.backend.agent.llm;

/**
 * The seam between the orchestrator and a concrete LLM provider. The orchestrator owns the
 * multi-turn loop; an {@code LlmClient} only knows how to do <b>one</b> request/response.
 *
 * <p>Swapping providers (stub -> Anthropic -> OpenAI) is changing which bean implements
 * this interface; the orchestrator never changes.
 */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);
}
