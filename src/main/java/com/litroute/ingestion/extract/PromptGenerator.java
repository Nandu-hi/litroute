package com.litroute.ingestion.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Builds the local model's prompt: the template in {@code src/main/resources/prompts.txt} with the email
 * body in place of its {@value #BODY} placeholder. The wording of the prompt lives only in that file, so it
 * can be changed without touching the code.
 *
 * Example:
 * <pre>{@code
 * template "Find the titles.\n<email>\n{body}\n</email>"  +  body "<p><a>Wave loads on piles</a></p>"
 * -> "Find the titles.\n<email>\n<p><a>Wave loads on piles</a></p>\n</email>"
 * }</pre>
 */
public final class PromptGenerator {

    static final String RESOURCE = "/prompts.txt";
    static final String BODY = "{body}";

    private final String template;

    /**
     * Input: the prompt template.
     * Output: a generator for it; throws unless it holds {@value #BODY} exactly once.
     * Used by: {@link #fromResource}; PromptGeneratorTest.
     * Example: {@code new PromptGenerator("Titles in: {body}")} -> ok; {@code new PromptGenerator("Titles")} -> throws
     */
    PromptGenerator(String template) {
        int first = template.indexOf(BODY);
        if (first < 0 || first != template.lastIndexOf(BODY)) {
            throw new IllegalStateException(RESOURCE.substring(1) + " must contain " + BODY + " exactly once");
        }
        this.template = template;
    }

    /**
     * Input: none.
     * Output: a generator for the template in prompts.txt; throws if the file is missing or has no {@value #BODY}.
     * Used by: AiTitleExtractor.fromEnvironment.
     * Example: {@code fromResource().generate("<p>...</p>")} -> the whole prompt, ending in the email's lines
     */
    public static PromptGenerator fromResource() {
        try (InputStream in = PromptGenerator.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE.substring(1) + " on the classpath");
            }
            return new PromptGenerator(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Input: the email body as the model should read it.
     * Output: the prompt; the body goes in literally, so a "{body}" written inside an email stays plain text.
     * Used by: AiTitleExtractor, for each slice of an email.
     * Example: "Titles in: {body}" + "<p>x</p>" -> "Titles in: <p>x</p>"
     */
    public String generate(String body) {
        return template.replace(BODY, body);
    }
}
