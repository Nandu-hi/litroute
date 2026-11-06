package com.litroute.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Output of M1 EmailSource: one Gmail message that looked like a publication alert, before any parsing.
 *
 * @param messageId     the RFC 822 Message-ID header; M7a's key against re-processing. Required.
 * @param subject       the Subject header
 * @param fromAddress   the sender's address
 * @param receivedAt    when Gmail received it, UTC
 * @param htmlBody      the first HTML part, if any
 * @param plainTextBody the first plain-text part, if any; at least one of the two bodies is present
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
