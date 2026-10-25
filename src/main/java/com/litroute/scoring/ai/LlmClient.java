package com.litroute.scoring.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/**
 * A language model that answers a prompt with JSON of a given shape. {@link AiPaperMatcher} talks only
 * to this interface, so the provider - Groq today ({@link GroqClient}) - can be
 * swapped without touching the matching.
 */
public interface LlmClient {

    /**
     * Names the model answering, e.g. {@code openai/gpt-oss-120b}; shown in the papers' rationales.
     * Input: none.
     * Output: the model answering, shown in the papers' rationales.
     * Used by: AiPaperMatcher (rationales).
     * Example: GroqClient -> "openai/gpt-oss-120b"
     */
    String modelId();

    /**
     * The longest prompt one request may carry, in tokens, so that it and the answer fit the API's token
     * window. {@link AiPaperMatcher} splits a batch into requests no longer than this.
     * Input: none.
     * Output: the longest prompt one request may carry, in tokens.
     * Used by: AiPaperMatcher.requests, to split a batch.
     * Example: Groq free tier -> 5500
     */
    int maxPromptTokens();

    /**
     * Input: the whole prompt and the JSON Schema the answer must follow.
     * Output: the model's parsed answer; IOException if unreachable, throttling, failing, refusing or not JSON.
     * Used by: AiPaperMatcher.ask (M6 AI).
     * Example: ("Match these papers ...", schema) -> {"profiles":[{"profile":"alice","matches":[{"paper_id":"P1","score":85}],"focus":{...}}]}
     */
    JsonNode generateJson(String prompt, ObjectNode responseSchema) throws IOException;
}
