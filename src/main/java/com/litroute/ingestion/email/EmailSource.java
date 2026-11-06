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
     * {@return the newly seen publication alerts, oldest first; empty if the mailbox can't be reached}
     *
     * @param credentials                Gmail credentials
     * @param alreadyProcessedMessageIds Message-IDs in M7a; skipped without being downloaded
     */
    List<RawEmail> fetchNewAlerts(GmailCredentials credentials, Set<String> alreadyProcessedMessageIds);

    /**
     * Marks these messages read. Call it only once they are in M7a: fetching sees only unread mail, so a
     * message marked read but never recorded would never be fetched again.
     *
     * @param credentials Gmail credentials
     * @param messageIds  the Message-IDs of the alerts just processed
     * @return how many were marked read; 0 if the mailbox can't be reached
     */
    int markRead(GmailCredentials credentials, Collection<String> messageIds);

    /**
     * Marks these messages unread again so the next fetch sees them; messages no longer in the inbox are skipped.
     * Used by: PipelineRunner.reset (M9), before M7a is cleared.
     *
     * @param credentials Gmail credentials
     * @param messageIds  the Message-IDs held in M7a
     * @return how many were found in the inbox, all now unread
     * @throws IOException if the mailbox failed; assume none were marked
     */
    int markUnread(GmailCredentials credentials, Collection<String> messageIds) throws IOException;
}
