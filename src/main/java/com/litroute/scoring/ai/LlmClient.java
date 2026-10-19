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

    /** Names the model answering, e.g. {@code openai/gpt-oss-120b}; shown in the papers' rationales. */
    String modelId();

    /**
     * The longest prompt one request may carry, in tokens, so that it and the answer fit the API's token
     * window. {@link AiPaperMatcher} splits a batch into requests no longer than this.
     */
    int maxPromptTokens();

    /**
     * @param prompt         the whole request, as text
     * @param responseSchema a JSON Schema the answer must follow
     * @return the model's answer, parsed
     * @throws IOException if the API can't be reached, is throttling or failing, refuses the prompt, or
     *         answers with something that is not JSON
     */
    JsonNode generateJson(String prompt, ObjectNode responseSchema) throws IOException;
}
