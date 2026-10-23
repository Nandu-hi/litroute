package com.litroute.ingestion.email;

import com.litroute.model.RawEmail;
import com.litroute.security.GmailCredentials;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * M1. Implementations must fail gracefully: a connection drop or rejected
 * credentials should be logged and result in an empty list (or nothing
 * marked), never a thrown exception that would take down the rest of the
 * pipeline.
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
}
