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
     * Input: the JSON file to keep this store in.
     * Output: a store for that file (nothing is read yet).
     * Used by: {@link #atDefaultLocation}; tests with a temp folder.
     * Example: {@code new PriorityAuthorStore(tmp.resolve("priority_authors.json")).load()} -> empty
     */
    public PriorityAuthorStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    /**
     * Input: none.
     * Output: a store for {@code ~/.litroute/data/priority_authors.json}.
     * Used by: PipelineRunner.atDefaultLocations (M9), PriorityAuthors.atDefaultLocations, RelevanceScorerCli.
     * Example: {@code atDefaultLocation()} -> store for /home/u/.litroute/data/priority_authors.json
     */
    public static PriorityAuthorStore atDefaultLocation() {
        return new PriorityAuthorStore(Path.of(System.getProperty("user.home"), ".litroute", "data", "priority_authors.json"));
    }

    /**
     * Input: none.
     * Output: a modifiable list of the names; empty if no file yet; IOException if corrupt.
     * Used by: PriorityAuthors (M9), PipelineRunner.atDefaultLocations, RelevanceScorerCli.
     * Example: file {@code [{"firstName":"Alice","lastName":"Carter"}]} -> {@code [Alice Carter]}
     */
    public List<AuthorName> load() throws IOException {
        if (!Files.exists(storeFile)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(JSON.readValue(Files.readAllBytes(storeFile), NAME_LIST));
    }

    /**
     * Input: the whole list.
     * Output: none; the file is atomically replaced with it.
     * Used by: PriorityAuthors (M9).
     * Example: {@code save([Alice Carter])} -> file {@code [{"firstName":"Alice","lastName":"Carter"}]}
     */
    public void save(List<AuthorName> names) throws IOException {
        AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(names));
    }
}
