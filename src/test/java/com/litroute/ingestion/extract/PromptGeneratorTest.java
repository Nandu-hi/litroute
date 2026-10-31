package com.litroute.ingestion.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PromptGeneratorTest {

    @Test
    public void theRealPromptTakesTheBodyAndAsksForTheTitlesAsJson() {
        String prompt = PromptGenerator.fromResource().generate("<p><a>Wave loads on piles</a></p>");

        assertTrue(prompt.contains("<email>\n<p><a>Wave loads on piles</a></p>\n</email>"));
        assertTrue(prompt.contains("{\"titles\":"));
        assertFalse("nothing left to fill in", prompt.contains(PromptGenerator.BODY));
    }

    @Test
    public void theBodyGoesInLiterally() {
        PromptGenerator prompts = new PromptGenerator("Read: {body}!");

        assertEquals("Read: costs $1 and {body} too!", prompts.generate("costs $1 and {body} too"));
    }

    @Test
    public void aTemplateMustHoldTheBodyExactlyOnce() {
        assertThrows(IllegalStateException.class, () -> new PromptGenerator("no placeholder"));
        assertThrows(IllegalStateException.class, () -> new PromptGenerator("{body} and {body}"));
    }
}
