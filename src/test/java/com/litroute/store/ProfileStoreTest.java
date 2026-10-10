package com.litroute.store;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.litroute.model.Profile;
import com.litroute.model.Profile.InterestCluster;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.testing.TestProfiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ProfileStoreTest {

    @Test
    public void missingFileMeansNoProfileYet() throws IOException {
        ProfileStore store = new ProfileStore(Files.createTempDirectory("litroute-test").resolve("profiles.json"));

        assertTrue(store.loadAll().isEmpty());
        assertTrue(store.load("anyone").isEmpty());
    }

    @Test
    public void builtProfilesRoundTripWithIdenticalVectors() throws IOException {
        Map<String, Profile> built = TestProfiles.profiles();
        ProfileStore store = new ProfileStore(Files.createTempDirectory("litroute-test").resolve("nested").resolve("profiles.json"));

        for (Profile profile : built.values()) {
            store.save(profile);
        }
        Map<String, Profile> loaded = store.loadAll();

        assertEquals(new ArrayList<>(built.keySet()), new ArrayList<>(loaded.keySet()));
        for (Profile expected : built.values()) {
            assertSameProfile(expected, loaded.get(expected.name()));
        }
    }

    @Test
    public void saveReplacesAProfileByNameAndRemoveDeletesOnlyThatOne() throws IOException {
        List<Profile> built = new ArrayList<>(TestProfiles.profiles().values());
        Profile first = built.get(0);
        ProfileStore store = new ProfileStore(Files.createTempDirectory("litroute-test").resolve("profiles.json"));
        store.save(first);
        store.save(renamed(first, "other-student"));

        Profile replaced = new Profile(first.name(), List.of("new@example.org"), first.papers(), first.clusters(),
                first.modelId(), first.updatedAt());
        store.save(replaced);

        assertEquals(List.of("new@example.org"), store.load(first.name()).orElseThrow().emails());
        assertEquals(2, store.loadAll().size());
        assertTrue(store.remove(first.name()));
        assertFalse(store.remove(first.name()));
        assertTrue(store.load(first.name()).isEmpty());
        assertTrue(store.load("other-student").isPresent());
    }

    /** A store written before paper text was kept still loads; its papers simply have no text (M5 adds it). */
    @Test
    public void storeSavedBeforeTextWasKeptStillLoads() throws IOException {
        Profile built = TestProfiles.profiles().values().iterator().next();
        List<ProfilePaper> vectorsOnly = built.papers().stream()
                .map(p -> new ProfilePaper(p.relativePath(), p.sha256(), p.title(), p.embedding(), p.abstractView(),
                        p.structureView(), p.bodyView()))
                .toList();
        Path file = Files.createTempDirectory("litroute-test").resolve("profiles.json");
        ProfileStore store = new ProfileStore(file);
        store.save(new Profile(built.name(), built.emails(), vectorsOnly, built.clusters(), built.modelId(), built.updatedAt()));

        assertFalse(Files.readString(file).contains("abstractText"));
        Profile loaded = store.load(built.name()).orElseThrow();
        assertEquals(built.papers().size(), loaded.papers().size());
        assertTrue(loaded.papers().stream().noneMatch(ProfilePaper::hasText));
        assertTrue("a real build keeps every paper's text", built.papers().stream().allMatch(ProfilePaper::hasText));
    }

    @Test(expected = IOException.class)
    public void corruptFileIsReportedNotTreatedAsEmpty() throws IOException {
        Path file = Files.createTempDirectory("litroute-test").resolve("profiles.json");
        Files.writeString(file, "{\"alice\": {\"name\": \"alice\", \"papers\": [");

        new ProfileStore(file).loadAll();
    }

    private static Profile renamed(Profile profile, String name) {
        return new Profile(name, profile.emails(), profile.papers(), profile.clusters(), profile.modelId(),
                profile.updatedAt());
    }

    private static void assertSameProfile(Profile expected, Profile actual) {
        assertEquals(expected.name(), actual.name());
        assertEquals(expected.emails(), actual.emails());
        assertEquals(expected.modelId(), actual.modelId());
        assertEquals(expected.updatedAt(), actual.updatedAt());
        assertEquals(expected.papers().size(), actual.papers().size());
        for (int i = 0; i < expected.papers().size(); i++) {
            ProfilePaper e = expected.papers().get(i);
            ProfilePaper a = actual.papers().get(i);
            assertEquals(e.relativePath(), a.relativePath());
            assertEquals(e.sha256(), a.sha256());
            assertEquals(e.title(), a.title());
            assertArrayEquals(e.embedding(), a.embedding(), 0f);
            assertArrayEquals(e.abstractView(), a.abstractView(), 0f);
            assertEquals(e.structureView().isPresent(), a.structureView().isPresent());
            e.structureView().ifPresent(v -> assertArrayEquals(v, a.structureView().get(), 0f));
            assertEquals(e.bodyView().isPresent(), a.bodyView().isPresent());
            e.bodyView().ifPresent(v -> assertArrayEquals(v, a.bodyView().get(), 0f));
            assertEquals(e.abstractText(), a.abstractText());
            assertEquals(e.bodyText(), a.bodyText());
        }
        assertEquals(expected.clusters().size(), actual.clusters().size());
        for (int i = 0; i < expected.clusters().size(); i++) {
            InterestCluster e = expected.clusters().get(i);
            InterestCluster a = actual.clusters().get(i);
            assertArrayEquals(e.centroid(), a.centroid(), 0f);
            assertEquals(e.memberSha256s(), a.memberSha256s());
        }
    }
}
