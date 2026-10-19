package com.litroute.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litroute.model.Profile;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M7d: the M5-to-M6 boundary, so a student's PDFs are read and embedded once rather than on every
 * run. Holds every profile in one JSON file, keyed by profile name.
 *
 * A missing file or a missing name is the "no profile yet" signal ({@link #loadAll()} returns an
 * empty map, {@link #load(String)} an empty Optional), prompting onboarding rather than failing.
 */
public final class ProfileStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, StoredProfile>> FILE_FORMAT = new TypeReference<>() {
    };

    /**
     * JSON shape of a Profile: plain nullable fields, so Jackson needs no Optional/Instant module.
     * Stores written while profiles were split into interest clusters still load: their clusters are
     * ignored, and dropped the next time the store is written.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties({"clusters"})
    private record StoredProfile(
            String name, List<String> emails, List<StoredPaper> papers, List<String> keywords, String modelId,
            String updatedAt) {

        static StoredProfile from(Profile profile) {
            return new StoredProfile(profile.name(), profile.emails(),
                    profile.papers().stream().map(StoredPaper::from).toList(), profile.keywords(), profile.modelId(),
                    profile.updatedAt().toString());
        }

        Profile toProfile() throws IOException {
            try {
                return new Profile(name, emails == null ? List.of() : emails,
                        papers.stream().map(StoredPaper::toPaper).toList(), keywords == null ? List.of() : keywords,
                        modelId, Instant.parse(updatedAt));
            } catch (RuntimeException e) {
                throw new IOException("Corrupt profile entry \"" + name + "\": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Text and keywords are absent in stores written before they were kept; such papers load without them.
     * A paper read for keywords that lists none stores an empty list.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record StoredPaper(
            String relativePath, String sha256, String title, float[] embedding, float[] abstractView,
            float[] structureView, float[] bodyView, String abstractText, String bodyText, List<String> keywords) {

        static StoredPaper from(ProfilePaper paper) {
            return new StoredPaper(paper.relativePath(), paper.sha256(), paper.title(), paper.embedding(),
                    paper.abstractView(), paper.structureView().orElse(null), paper.bodyView().orElse(null),
                    paper.abstractText().orElse(null), paper.bodyText().orElse(null), paper.keywords().orElse(null));
        }

        ProfilePaper toPaper() {
            return new ProfilePaper(relativePath, sha256, title, embedding, abstractView,
                    Optional.ofNullable(structureView), Optional.ofNullable(bodyView), Optional.ofNullable(abstractText),
                    Optional.ofNullable(bodyText), Optional.ofNullable(keywords));
        }
    }

    private final Path storeFile;

    public ProfileStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    public static ProfileStore atDefaultLocation() {
        return new ProfileStore(Path.of(System.getProperty("user.home"), ".litroute", "data", "profiles.json"));
    }

    /**
     * @return every saved profile by name, in the order they were first saved; empty if none yet
     * @throws IOException if the file exists but is unreadable or not a valid profile store
     */
    public Map<String, Profile> loadAll() throws IOException {
        Map<String, Profile> profiles = new LinkedHashMap<>();
        if (!Files.exists(storeFile)) {
            return profiles;
        }
        for (Map.Entry<String, StoredProfile> entry : JSON.readValue(Files.readAllBytes(storeFile), FILE_FORMAT).entrySet()) {
            profiles.put(entry.getKey(), entry.getValue().toProfile());
        }
        return profiles;
    }

    /** @return the named profile, or empty if it has not been built yet */
    public Optional<Profile> load(String name) throws IOException {
        return Optional.ofNullable(loadAll().get(name));
    }

    /** Adds the profile, or replaces the saved one with the same name. */
    public void save(Profile profile) throws IOException {
        Map<String, Profile> profiles = loadAll();
        profiles.put(profile.name(), profile);
        write(profiles);
    }

    /** @return whether a profile by that name existed */
    public boolean remove(String name) throws IOException {
        Map<String, Profile> profiles = loadAll();
        boolean existed = profiles.remove(name) != null;
        if (existed) {
            write(profiles);
        }
        return existed;
    }

    private void write(Map<String, Profile> profiles) throws IOException {
        Map<String, StoredProfile> file = new LinkedHashMap<>();
        profiles.forEach((name, profile) -> file.put(name, StoredProfile.from(profile)));
        AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(file));
    }
}
