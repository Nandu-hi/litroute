package com.litroute.ingestion.extract;

/**
 * What an alert email's link to a paper lets us do without any lookup.
 */
public enum LinkKind {
    /** A direct publisher URL, or a redirect wrapper whose real target could be decoded. */
    READABLE,
    /** A click-tracking token with no recoverable target ("encrypted"): only a title lookup can identify the paper. */
    OPAQUE,
    /** The email gave no usable http(s) link for the paper. */
    NONE
}
