package com.litroute.model;

/** Where a scored paper stands in the professor's review queue (M8). */
public enum ReviewStatus {
    /** Scored and waiting for the professor. */
    PENDING,
    /** To be sent to the student (M10). */
    APPROVED,
    /** Not to be sent. */
    DROPPED,
    /** Approved and emailed to the student (M10): held in {@code SentPaperStore}, no longer in the queue. */
    SENT,
    /**
     * M6 matched it to no one (below the relevance floor, or not a research paper): kept under the student it is
     * closest to and listed with the dropped papers, so the professor can still approve it or move it to a student.
     */
    UNMATCHED
}
