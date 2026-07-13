package com.assettracker.backend.agent.llm;

/** One request/response to the model. Swap beans to change providers. */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);
}
