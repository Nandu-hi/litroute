package com.litroute.ingestion.extract;

/** How M2 finds the papers in an alert email; chosen per run. */
public enum ExtractionMode {
    /** {@link AlertHtmlParser}'s pattern matching: offline, needs nothing installed. */
    PATTERN("Pattern matching"),
    /** The local language model ({@link AiTitleExtractor}) decides which papers an email lists; needs Ollama. */
    LLM("Local AI model");

    private final String label;

    /**
     * Input: the label shown in the UI.
     * Output: the mode.
     * Used by: the enum constants.
     * Example: LLM -> "Local AI model"
     */
    ExtractionMode(String label) {
        this.label = label;
    }

    /**
     * Input: none.
     * Output: the UI label.
     * Used by: PipelinePane's toggle (M8) and its log line.
     * Example: PATTERN.toString() -> "Pattern matching"
     */
    @Override
    public String toString() {
        return label;
    }
}
