package com.litroute.ingestion.email;

import com.litroute.model.RawEmail;
import com.litroute.security.GmailCredentials;
import java.util.List;
import java.util.Set;

/**
 * M1. Implementations must fail gracefully: a connection drop or rejected
 * credentials should be logged and result in an empty list, never a thrown
 * exception that would take down the rest of the pipeline.
 */
public interface EmailSource {

    /**
     * @param alreadyProcessedMessageIds Message-IDs already recorded in M7a;
     *         matching messages are skipped without being parsed.
     * @return newly-seen publication alert emails, oldest first.
     */
    List<RawEmail> fetchNewAlerts(GmailCredentials credentials, Set<String> alreadyProcessedMessageIds);
}
