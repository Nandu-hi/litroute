package com.litroute.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.litroute.model.PaperCandidate;
import com.litroute.store.EnrichmentCacheStore.Contents;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EnrichmentCacheStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EnrichmentCacheStore store() {
        return new EnrichmentCacheStore(tmp.getRoot().toPath().resolve("nested").resolve("enrichment_cache.json"));
    }

    @Test
    public void loadReturnsEmptyContentsWhenFileDoesNotExist() throws IOException {
        Contents contents = store().load();

        assertTrue(contents.enriched().isEmpty());
        assertTrue(contents.lookups().isEmpty());
    }

    @Test
    public void enrichedRecordsAndLookupCacheAreLoadedBackEqual() throws IOException {
        EnrichmentCacheStore store = store();
        PaperCandidate full = Candidates.full();
        PaperCandidate sparse = Candidates.sparse();
        Contents saved = new Contents(List.of(full, sparse), Map.of("hygrothermalanalysis", full));

        store.save(saved);
        Contents loaded = store.load();

        assertEquals(saved.enriched(), loaded.enriched());
        assertEquals(saved.lookups(), loaded.lookups());
    }

    @Test
    public void loadedContentsCanBeAddedToAndSavedAgain() throws IOException {
        EnrichmentCacheStore store = store();
        store.save(Contents.empty());

        Contents contents = store.load();
        contents.enriched().add(Candidates.full());
        contents.lookups().put("key", Candidates.full());
        store.save(contents);

        Contents reloaded = store.load();
        assertEquals(1, reloaded.enriched().size());
        assertEquals(Map.of("key", Candidates.full()), reloaded.lookups());
    }

    @Test
    public void saveReplacesPreviousContents() throws IOException {
        EnrichmentCacheStore store = store();
        store.save(new Contents(List.of(Candidates.full()), Map.of()));

        store.save(new Contents(List.of(Candidates.sparse()), Map.of()));

        assertEquals(List.of(Candidates.sparse()), store.load().enriched());
    }

    @Test
    public void fileMissingOneSectionStillLoads() throws IOException {
        Path file = tmp.getRoot().toPath().resolve("enrichment_cache.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);

        Contents contents = new EnrichmentCacheStore(file).load();

        assertTrue(contents.enriched().isEmpty());
        assertTrue(contents.lookups().isEmpty());
    }

    @Test
    public void unreadableFileIsReportedAsIOException() throws IOException {
        Path file = tmp.getRoot().toPath().resolve("enrichment_cache.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new EnrichmentCacheStore(file).load());
    }

    @Test
    public void aStoredEntryThatLostItsTitleIsReportedAsIOException() throws IOException {
        Path file = tmp.getRoot().toPath().resolve("enrichment_cache.json");
        Files.writeString(file, "{\"enriched\":[{\"sourceMessageId\":\"<m1@mail>\"}]}", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new EnrichmentCacheStore(file).load());
    }
}
