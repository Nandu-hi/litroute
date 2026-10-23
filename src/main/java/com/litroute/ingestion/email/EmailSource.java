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
     * @param alreadyProcessedMessageIds Message-IDs already recorded in M7a;
     *         matching messages are skipped without being parsed.
     * @return newly-seen publication alert emails, oldest first.
     */
    List<RawEmail> fetchNewAlerts(GmailCredentials credentials, Set<String> alreadyProcessedMessageIds);

    /**
     * Marks these messages read in the mailbox. Fetching leaves mail unread
     * and only looks at unread mail, so call this only once the messages are
     * recorded in M7a: marked any earlier, a message the run then failed to
     * record would never be fetched again.
     *
     * @param messageIds Message-IDs of alerts the pipeline has processed
     * @return how many messages were marked read; 0 if the mailbox could not be reached
     */
    int markRead(GmailCredentials credentials, Collection<String> messageIds);

    /**
     * Marks these messages unread again, read or not, so the next fetch sees them: what re-running the
     * pipeline over mail it has already processed needs. Messages no longer in the inbox are skipped.
     *
     * @param messageIds Message-IDs of alerts the pipeline has processed
     * @return how many of them were found in the inbox; each of those is unread afterwards
     * @throws IOException if the mailbox could not be reached or refused the change; then the caller must
     *         not assume any message is unread
     */
    int markUnread(GmailCredentials credentials, Collection<String> messageIds) throws IOException;
}
