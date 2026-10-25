package com.litroute.profile;

/**
 * A reference PDF that cannot contribute to a profile: corrupt or truncated, password-protected, or
 * with no extractable text (a scan without OCR). M5 logs it and builds the profile from the rest.
 */
public final class UnreadablePdfException extends Exception {

    /**
     * Input: why the PDF can't be used.
     * Output: the exception.
     * Used by: PdfTextExtractor.extract (no text, not words).
     * Example: {@code new UnreadablePdfException("no extractable text (a scan without OCR?)")}
     */
    public UnreadablePdfException(String message) {
        super(message);
    }

    /**
     * Input: why, and the underlying error.
     * Output: the exception.
     * Used by: PdfTextExtractor.extract (password, corrupt file).
     * Example: {@code new UnreadablePdfException("password-protected", e)}
     */
    public UnreadablePdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
