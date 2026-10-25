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

        /**
         * Input: a profile.
         * Output: its JSON shape (updatedAt as an ISO string).
         * Used by: {@link ProfileStore#write}.
         * Example: profile updated 2026-10-01T09:00Z -> {@code updatedAt = "2026-10-01T09:00:00Z"}
         */
        static StoredProfile from(Profile profile) {
            return new StoredProfile(profile.name(), profile.emails(),
                    profile.papers().stream().map(StoredPaper::from).toList(), profile.keywords(), profile.modelId(),
                    profile.updatedAt().toString());
        }

        /**
         * Input: none (this stored entry).
         * Output: the profile (missing emails/keywords become []); IOException if the entry is invalid.
         * Used by: {@link ProfileStore#loadAll}.
         * Example: entry with no "keywords" field -> Profile with keywords []
         */
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

        /**
         * Input: a profile paper.
         * Output: its JSON shape; absent views, text and keywords become null (left out of the file).
         * Used by: {@link StoredProfile#from}.
         * Example: paper with no bodyView -> StoredPaper with {@code bodyView == null}
         */
        static StoredPaper from(ProfilePaper paper) {
            return new StoredPaper(paper.relativePath(), paper.sha256(), paper.title(), paper.embedding(),
                    paper.abstractView(), paper.structureView().orElse(null), paper.bodyView().orElse(null),
                    paper.abstractText().orElse(null), paper.bodyText().orElse(null), paper.keywords().orElse(null));
        }

        /**
         * Input: none (this stored entry).
         * Output: the profile paper, with null fields as empty optionals.
         * Used by: {@link StoredProfile#toProfile}.
         * Example: entry without abstractText (old store) -> paper with {@code hasText() == false}
         */
        ProfilePaper toPaper() {
            return new ProfilePaper(relativePath, sha256, title, embedding, abstractView,
                    Optional.ofNullable(structureView), Optional.ofNullable(bodyView), Optional.ofNullable(abstractText),
                    Optional.ofNullable(bodyText), Optional.ofNullable(keywords));
        }
    }

    private final Path storeFile;

    /**
     * Input: the JSON file to keep this store in.
     * Output: a store for that file (nothing is read yet).
     * Used by: {@link #atDefaultLocation}; tests with a temp folder.
     * Example: {@code new ProfileStore(tmp.resolve("profiles.json")).loadAll()} -> empty
     */
    public ProfileStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    /**
     * Input: none.
     * Output: a store for {@code ~/.litroute/data/profiles.json}.
     * Used by: PipelineRunner.atDefaultLocations (M9), ProfilesPane/ReviewPane (M8), ApprovedPaperSender (M10), ProfileBuilderCli, RelevanceScorerCli.
     * Example: {@code atDefaultLocation()} -> store for /home/u/.litroute/data/profiles.json
     */
    public static ProfileStore atDefaultLocation() {
        return new ProfileStore(Path.of(System.getProperty("user.home"), ".litroute", "data", "profiles.json"));
    }

    /**
     * Input: none.
     * Output: every saved profile by name, in first-saved order; empty if none yet; IOException if corrupt.
     * Used by: ProfileBuilder.syncAll (M5), PipelineRunner (M9) for M6, ApprovedPaperSender (M10) for addresses, ProfilesPane (M8).
     * Example: file {@code {"alice":{...},"bob":{...}}} -> {@code {alice=Profile, bob=Profile}}
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

    /**
     * Input: a profile name.
     * Output: that profile, or empty if it has not been built yet.
     * Used by: tests (callers in main use {@link #loadAll}).
     * Example: {@code load("alice")} -> {@code Optional[alice]}; {@code load("zoe")} -> empty
     */
    public Optional<Profile> load(String name) throws IOException {
        return Optional.ofNullable(loadAll().get(name));
    }

    /**
     * Adds the profile, or replaces the saved one with the same name.
     * Input: a profile.
     * Output: none; the file is rewritten with it.
     * Used by: ProfileBuilder (M5) after (re)embedding a folder.
     * Example: {@code save(alice')} where alice exists -> alice replaced, still first in order
     */
    public void save(Profile profile) throws IOException {
        Map<String, Profile> profiles = loadAll();
        profiles.put(profile.name(), profile);
        write(profiles);
    }

    /**
     * Input: a profile name.
     * Output: whether it existed; if so, the file is rewritten without it.
     * Used by: ProfileBuilder (M5) when a folder is deleted or has no readable PDFs.
     * Example: {@code remove("bob")} -> true; again -> false
     */
    public boolean remove(String name) throws IOException {
        Map<String, Profile> profiles = loadAll();
        boolean existed = profiles.remove(name) != null;
        if (existed) {
            write(profiles);
        }
        return existed;
    }

    /**
     * Input: every profile to keep.
     * Output: none; the file is atomically replaced.
     * Used by: {@link #save}, {@link #remove}.
     * Example: {@code write({alice})} -> file {@code {"alice":{...}}}
     */
    private void write(Map<String, Profile> profiles) throws IOException {
        Map<String, StoredProfile> file = new LinkedHashMap<>();
        profiles.forEach((name, profile) -> file.put(name, StoredProfile.from(profile)));
        AtomicFiles.writeAtomically(storeFile, JSON.writeValueAsBytes(file));
    }
}
