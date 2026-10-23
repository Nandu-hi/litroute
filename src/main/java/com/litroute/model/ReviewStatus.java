package com.litroute.model;

/** Where a scored paper stands in the professor's review queue (M8). */
public enum ReviewStatus {
    /** Scored and waiting for the professor. */
    PENDING,
    /** To be sent to the student (M10). */
    APPROVED,
    /** Not to be sent. */
    DROPPED,
    /** Approved and emailed to the student (M10); approving it again sends it again. */
    SENT
}
