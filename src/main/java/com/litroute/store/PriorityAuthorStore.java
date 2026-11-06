package com.litroute.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.model.AuthorName;
import com.litroute.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The professor's list of authors whose papers M6 favours, each a first and a last name, in the order they
 * were added. It is a setting, not a cache: nothing derives it, and the Caches tab leaves it alone.
 */
public final class PriorityAuthorStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<AuthorName>> NAME_LIST = new TypeReference<>() {
    };

    private final Path storeFile;

    /**
     * Nothing is read until {@link #load}.
     *
     * @param storeFile the JSON file to keep this store in
     */
    public PriorityAuthorStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    /** {@return a store for {@code ~/.litroute/data/priority_authors.json}} */
    public static PriorityAuthorStore atDefaultLocation() {
        return new PriorityAuthorStore(Path.of(System.getProperty("user.home"), ".litroute", "data", "priority_authors.json"));
    }

    /**
     * {@return a modifiable list of the names; empty if there is no file yet}
     *
     * @throws IOException if the file can't be read or is corrupt
     */
    public List<AuthorName> load() throws IOException {
        if (!Files.exists(storeFile)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(JSON.readValue(Files.readAllBytes(storeFile), NAME_LIST));
    }

    /**
     * Atomically replaces the file with the whole list.
     *
     * @param names the whole list
     * @throws IOException if the file can't be written
     */
    public void save(List<AuthorName> names) throws IOException {
        AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(names));
    }
}
