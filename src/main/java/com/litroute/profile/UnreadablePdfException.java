package com.litroute.profile;

/**
 * A reference PDF that cannot contribute to a profile: corrupt or truncated, password-protected, or
 * with no extractable text (a scan without OCR). M5 logs it and builds the profile from the rest.
 */
public final class UnreadablePdfException extends Exception {

    public UnreadablePdfException(String message) {
        super(message);
    }

    public UnreadablePdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
