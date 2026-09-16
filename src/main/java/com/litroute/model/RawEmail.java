package com.litroute.model;

import java.time.Instant;
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
        Instant receivedAt,
        Optional<String> htmlBody,
        Optional<String> plainTextBody) {

    public RawEmail {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (htmlBody.isEmpty() && plainTextBody.isEmpty()) {
            throw new IllegalArgumentException("RawEmail must have at least one of htmlBody/plainTextBody");
        }
    }
}
