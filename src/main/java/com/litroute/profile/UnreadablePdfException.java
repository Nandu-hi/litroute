package com.litroute.profile;

/**
 * A reference PDF that cannot contribute to a profile: corrupt or truncated, password-protected, or
 * with no extractable text (a scan without OCR). M5 logs it and builds the profile from the rest.
 */
public final class UnreadablePdfException extends Exception {

    /**
     * An unreadable PDF.
     *
     * @param message why the PDF can't be used; shown as the skip reason
     */
    public UnreadablePdfException(String message) {
        super(message);
    }

    /**
     * An unreadable PDF, with the error that showed it.
     *
     * @param message why the PDF can't be used; shown as the skip reason
     * @param cause   the underlying error (password, corrupt file)
     */
    public UnreadablePdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
