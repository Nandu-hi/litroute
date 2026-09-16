package com.litroute.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Test;

public class EmailCacheStoreTest {

    @Test
    public void loadReturnsEmptySetWhenFileDoesNotExist() throws IOException {
        Path missingFile = Files.createTempDirectory("litroute-test").resolve("does-not-exist.json");
        EmailCacheStore store = new EmailCacheStore(missingFile);

        assertTrue(store.load().isEmpty());
    }

    @Test
    public void savedIdsAreLoadedBackUnchanged() throws IOException {
        Path file = Files.createTempDirectory("litroute-test").resolve("nested").resolve("email_cache.json");
        EmailCacheStore store = new EmailCacheStore(file);
        Set<String> messageIds = Set.of("<a@mail.gmail.com>", "<b@mail.gmail.com>");

        store.save(messageIds);

        assertEquals(messageIds, store.load());
    }

    @Test
    public void saveOverwritesPreviousContentsAtomically() throws IOException {
        Path file = Files.createTempDirectory("litroute-test").resolve("email_cache.json");
        EmailCacheStore store = new EmailCacheStore(file);

        store.save(Set.of("<old@mail.gmail.com>"));
        store.save(Set.of("<new@mail.gmail.com>"));

        assertEquals(Set.of("<new@mail.gmail.com>"), store.load());
    }
}
