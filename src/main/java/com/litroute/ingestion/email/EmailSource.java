package com.litroute.ingestion.email;

import com.litroute.model.RawEmail;
import com.litroute.security.GmailCredentials;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * M1. Implementations must fail gracefully: a connection drop or rejected
 * credentials should be logged and result in an empty list (or nothing
 * marked), never a thrown exception that would take down the rest of the
 * pipeline. The one exception is {@link #markUnread}, whose caller must know
 * whether it worked before it forgets the mail.
 */
public interface EmailSource {

    /**
     * Input: Gmail credentials and the Message-IDs already recorded in M7a (skipped without being parsed).
     * Output: the newly seen publication alerts, oldest first; empty if the mailbox can't be reached.
     * Used by: PipelineRunner.run (M9), step 2.
     * Example: inbox has 2 unread alerts, one ID in M7a -> {@code [RawEmail(the other one)]}
     */
    List<RawEmail> fetchNewAlerts(GmailCredentials credentials, Set<String> alreadyProcessedMessageIds);

    /**
     * Marks these messages read. Fetching only looks at unread mail, so call this only once they are in M7a:
     * marked any earlier, a message the run then failed to record would never be fetched again.
     * Input: credentials and the Message-IDs of the alerts just processed.
     * Output: how many were marked read; 0 if the mailbox could not be reached.
     * Used by: PipelineRunner.run (M9), step 4, unless "keep emails unread" is on.
     * Example: {@code markRead(c, ["<a@x>","<b@x>"])} -> {@code 2}
     */
    int markRead(GmailCredentials credentials, Collection<String> messageIds);

    /**
     * Marks these messages unread again so the next fetch sees them; messages no longer in the inbox are skipped.
     * Input: credentials and the Message-IDs held in M7a.
     * Output: how many were found in the inbox (all now unread); IOException if the mailbox failed (assume none are).
     * Used by: PipelineRunner.reset (M9, Clear and re-run), before M7a is cleared.
     * Example: 6 IDs, 5 still in the inbox -> {@code 5}
     */
    int markUnread(GmailCredentials credentials, Collection<String> messageIds) throws IOException;
}
