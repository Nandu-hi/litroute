package com.litroute.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Standardized output of M1 EmailSource: a single Gmail message that looked
 * like a publication alert, before any HTML parsing (that's M2's job).
 *
 * messageId is the RFC 822 "Message-ID" header value, used by M7a to avoid
 * re-processing the same email on later pipeline runs.
 */
public record RawEmail(
        String messageId,
        String subject,
        String fromAddress,
        Instant receivedAt, //UTC timestamp
        Optional<String> htmlBody,
        Optional<String> plainTextBody) {

    /**
     * Input: the message's headers and bodies; messageId non-blank, at least one body present.
     * Output: the email; throws if messageId is blank or both bodies are empty.
     * Used by: GmailEmailSource (M1) for each fetched alert; read by AlertBatchProcessor (M2) and M7a.
     * Example: {@code new RawEmail("<a@b>", "New articles", "alerts@x.org", now, Optional.of("<html>"), Optional.empty())} -> ok
     */
    public RawEmail {
        if (messageId == null || messageId.isBlank() || messageId.isEmpty() ) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        Objects.requireNonNull(htmlBody, "htmlBody must not be null");
        Objects.requireNonNull(plainTextBody, "plainTextBody must not be null");
        if (htmlBody.isEmpty() && plainTextBody.isEmpty()) {
            throw new IllegalArgumentException("RawEmail must have at least one of htmlBody/plainTextBody");
        }
    }
}
