package com.litroute.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.model.PaperCandidate;
import com.litroute.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M7c: the only store candidates ever reach. A paper is written here once it has been enriched (M3)
 * and deduplicated (M4), so the file never holds half-finished work. One JSON file with two parts:
 *
 * <ul>
 *   <li>{@code enriched}: the enriched, deduplicated candidates handed on to scoring;
 *   <li>{@code lookups}: the lookup cache, enriched candidates keyed by a normalized title (see
 *       {@code CachingPaperEnricher}), so a paper seen again is answered from disk instead of being
 *       looked up again over the network.
 * </ul>
 *
 * The store only persists; deciding what goes into either part is the pipeline's job - the lookup
 * cache is filled by the enricher and the candidate list by the deduplicator.
 */
public final class EnrichmentCacheStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Both collections are modifiable copies, so the pipeline can add to them and save() them back.
     * The lookups map is the live cache a CachingPaperEnricher reads and writes.
     */
    public record Contents(List<PaperCandidate> enriched, Map<String, PaperCandidate> lookups) {

        public Contents {
            enriched = new ArrayList<>(enriched);
            lookups = new LinkedHashMap<>(lookups);
        }

        public static Contents empty() {
            return new Contents(List.of(), Map.of());
        }
    }

    /** What is on disk; either part may be absent in a hand-edited file. */
    private record FileFormat(List<StoredCandidate> enriched, Map<String, StoredCandidate> lookups) {
    }

    private final Path storeFile;

    public EnrichmentCacheStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    public static EnrichmentCacheStore atDefaultLocation() {
        return new EnrichmentCacheStore(
                Path.of(System.getProperty("user.home"), ".litroute", "data", "enrichment_cache.json"));
    }

    /**
     * Returns empty contents if nothing has been saved yet.
     *
     * @throws IOException if the file exists but is unreadable or not a valid cache
     */
    public Contents load() throws IOException {
        if (!Files.exists(storeFile)) {
            return Contents.empty();
        }
        FileFormat file = JSON.readValue(Files.readAllBytes(storeFile), FileFormat.class);
        Map<String, PaperCandidate> lookups = new LinkedHashMap<>();
        if (file.lookups() != null) {
            for (Map.Entry<String, StoredCandidate> entry : file.lookups().entrySet()) {
                lookups.put(entry.getKey(), entry.getValue().toCandidate());
            }
        }
        List<PaperCandidate> enriched =
                file.enriched() == null ? List.of() : StoredCandidate.toCandidates(file.enriched());
        return new Contents(enriched, lookups);
    }

    public void save(Contents contents) throws IOException {
        Map<String, StoredCandidate> lookups = new LinkedHashMap<>();
        contents.lookups().forEach((key, candidate) -> lookups.put(key, StoredCandidate.from(candidate)));
        FileFormat file = new FileFormat(contents.enriched().stream().map(StoredCandidate::from).toList(), lookups);
        AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(file));
    }
}
