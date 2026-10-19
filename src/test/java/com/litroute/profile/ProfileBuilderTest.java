package com.litroute.profile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.litroute.embedding.Specter2EmbeddingProvider;
import com.litroute.model.Profile;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.profile.ProfileBuilder.BuildReport;
import com.litroute.profile.ProfileBuilder.ProfileReport;
import com.litroute.profile.ProfileBuilder.SkippedPdf;
import com.litroute.testing.TestProfiles;
import com.litroute.testing.TestProfiles.Workspace;
import com.litroute.util.Titles;
import com.litroute.util.Vectors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.Test;

/** Builds the real test profiles with the real model, then edits private copies of their folders. */
public class ProfileBuilderTest {

    /** Copies of one paper saved under two file names embed (all but) identically. */
    private static final double SAME_PAPER = 0.99;

    /** A real PDF download that was cut off after 8 KB. */
    private static final Path TRUNCATED_PDF = Path.of("src/test/resources/http/8d0d08f9b152568722f5.pdf");

    @Test
    public void everyFolderWithAReadablePdfBecomesAProfileOfDenseVectors() {
        BuildReport report = TestProfiles.firstBuild();
        Map<String, Profile> profiles = TestProfiles.profiles();

        assertEquals(TestProfiles.folders(TestProfiles.source()).size(), report.profiles().size());
        for (ProfileReport profileReport : report.profiles()) {
            int pdfs = TestProfiles.allPdfs(TestProfiles.source().resolve(profileReport.name())).size();
            assertEquals(profileReport.name(), pdfs,
                    profileReport.embedded() + profileReport.reused() + profileReport.skipped().size());
            assertEquals(profileReport.stored(), profiles.containsKey(profileReport.name()));
        }
        for (Profile profile : profiles.values()) {
            assertFalse(profile.papers().isEmpty());
            for (ProfilePaper paper : profile.papers()) {
                assertEquals(768, paper.embedding().length);
                assertEquals(paper.relativePath(), 1.0, Vectors.norm(paper.embedding()), 1e-5);
                assertFalse(paper.title().isBlank());
                assertTrue(paper.relativePath(), paper.hasText());
                assertFalse(paper.relativePath(), paper.abstractText().orElseThrow().isBlank());
                assertTrue(paper.relativePath(), paper.hasKeywords());
            }
        }
    }

    @Test
    public void storedEmailsComeFromTheFoldersEmailFile() throws IOException {
        for (Profile profile : TestProfiles.profiles().values()) {
            Path emailFile = TestProfiles.source().resolve(profile.name()).resolve(ProfileBuilder.EMAIL_FILE);
            if (!Files.exists(emailFile)) {
                assertTrue(profile.emails().isEmpty());
                continue;
            }
            String contents = Files.readString(emailFile);
            for (String email : profile.emails()) {
                assertTrue(email, contents.contains(email));
            }
        }
    }

    @Test
    public void emailFileSkipsCommentsBlanksDuplicatesAndNonAddresses() throws IOException {
        Path file = Files.createTempDirectory("litroute-emails").resolve(ProfileBuilder.EMAIL_FILE);
        Files.writeString(file, "# the student\ncs24b027@smail.iitm.ac.in\n\n  CS24B027@smail.iitm.ac.in  \n"
                + "advisor@example.org, second@example.org; not-an-address\n");

        assertEquals(List.of("cs24b027@smail.iitm.ac.in", "advisor@example.org", "second@example.org"),
                ProfileBuilder.readEmails(file));
        assertEquals(List.of(), ProfileBuilder.readEmails(file.resolveSibling("missing.txt")));
    }

    /**
     * The point of a profile: a paper from one student's folder must look more like that student's
     * papers as a whole than like anyone else's. Leave-one-out: each paper is compared with the mean
     * of its own folder's other papers (without any near-identical copy of it), so it never matches
     * itself. Students in one field overlap - multi-fidelity simulation and uncertainty
     * quantification, say - so the bar is a clear majority first and nearly all in the top two.
     */
    @Test
    public void papersRouteToTheirOwnProfile() {
        Map<String, Profile> profiles = TestProfiles.profiles();
        assumeTrue("needs at least two test profiles", profiles.size() >= 2);
        Map<String, Integer> profilesPerPaper = new HashMap<>();
        profiles.values().forEach(p -> p.papers().forEach(paper -> profilesPerPaper.merge(paper.sha256(), 1, Integer::sum)));

        List<String> misses = new ArrayList<>();
        int checked = 0;
        int topTwo = 0;
        for (Profile own : profiles.values()) {
            for (ProfilePaper paper : own.papers()) {
                List<ProfilePaper> rest = own.papers().stream()
                        .filter(other -> Vectors.cosine(paper.embedding(), other.embedding()) < SAME_PAPER).toList();
                if (profilesPerPaper.get(paper.sha256()) > 1 || rest.isEmpty()) {
                    continue; // a PDF in two folders belongs to both; a lone paper has nothing to match
                }
                checked++;
                double ownScore = Vectors.cosine(paper.embedding(), mean(rest));
                List<String> closer = new ArrayList<>();
                for (Profile other : profiles.values()) {
                    if (other != own && Vectors.cosine(paper.embedding(), mean(other.papers())) > ownScore) {
                        closer.add(other.name());
                    }
                }
                if (!closer.isEmpty()) {
                    misses.add(own.name() + "/" + paper.relativePath() + " is closer to " + closer);
                }
                if (closer.size() <= 1) {
                    topTwo++;
                }
            }
        }
        assumeTrue("no profile has two distinct papers", checked > 0);
        String detail = (checked - misses.size()) + " of " + checked + " papers nearest to their own profile, "
                + topTwo + " within the top two; misses:\n" + String.join("\n", misses);
        assertTrue(detail, checked - misses.size() >= checked * 0.70);
        assertTrue(detail, topTwo >= checked * 0.85);
    }

    @Test
    public void secondSyncReusesEveryEmbeddingAndLeavesUnchangedProfilesAlone() throws IOException {
        Workspace workspace = TestProfiles.freshWorkspace();
        Map<String, Profile> before = workspace.store().loadAll();

        BuildReport report = workspace.builder().syncAll();

        for (ProfileReport profile : report.profiles()) {
            assertEquals(profile.name(), 0, profile.embedded());
        }
        assertTrue(report.removed().isEmpty());
        for (Profile profile : workspace.store().loadAll().values()) {
            assertEquals(profile.name(), before.get(profile.name()).updatedAt(), profile.updatedAt());
        }
    }

    @Test
    public void changedPdfIsTheOnlyOneEmbeddedAgain() throws IOException {
        Workspace workspace = TestProfiles.freshWorkspace();
        Profile profile = smallestProfile();
        Path pdf = workspace.writableCopy(workspace.folder(profile.name()).resolve(profile.papers().get(0).relativePath()));
        // An incremental-update comment after %%EOF: same document, different bytes.
        Files.writeString(pdf, "\n% revised copy\n", StandardOpenOption.APPEND);

        ProfileReport report = reportFor(workspace.builder().syncAll(), profile.name());

        assertEquals(1, report.embedded());
        assertEquals(profile.papers().size() - 1, report.reused());
    }

    @Test
    public void unreadablePdfsAreReportedAndTheRestOfTheProfileIsStillBuilt() throws IOException {
        Workspace workspace = TestProfiles.freshWorkspace();
        Profile profile = smallestProfile();
        Files.copy(TRUNCATED_PDF, workspace.folder(profile.name()).resolve("zz-truncated.pdf"));

        ProfileReport report = reportFor(workspace.builder().syncAll(), profile.name());

        assertTrue(report.stored());
        assertTrue(report.skipped().stream().map(SkippedPdf::relativePath).toList().contains("zz-truncated.pdf"));
        assertEquals(profile.papers().size(), workspace.store().load(profile.name()).orElseThrow().papers().size());
    }

    @Test
    public void deletedFolderRemovesItsProfile() throws IOException {
        Workspace workspace = TestProfiles.freshWorkspace();
        String name = smallestProfile().name();
        deleteTree(workspace.folder(name));

        BuildReport report = workspace.builder().syncAll();

        assertEquals(List.of(name), report.removed());
        assertTrue(workspace.store().load(name).isEmpty());
        assertEquals(TestProfiles.profiles().size() - 1, workspace.store().loadAll().size());
    }

    @Test
    public void folderWithoutReadablePdfsIsNotSaved() throws IOException {
        Workspace workspace = TestProfiles.freshWorkspace();
        Path folder = Files.createDirectories(workspace.root().resolve("zz-new-student"));
        Files.writeString(folder.resolve(ProfileBuilder.EMAIL_FILE), "new.student@example.org\n");
        Files.copy(TRUNCATED_PDF, folder.resolve("truncated.pdf"));

        ProfileReport report = reportFor(workspace.builder().syncAll(), "zz-new-student");

        assertFalse(report.stored());
        assertEquals(1, report.skipped().size());
        assertTrue(workspace.store().load("zz-new-student").isEmpty());
    }

    @Test
    public void newFolderOfKnownPapersBecomesAProfileWithoutEmbedding() throws IOException {
        Workspace workspace = TestProfiles.freshWorkspace();
        Profile source = smallestProfile();
        Path folder = Files.createDirectories(workspace.root().resolve("zz-copy"));
        for (ProfilePaper paper : source.papers()) {
            Path from = TestProfiles.source().resolve(source.name()).resolve(paper.relativePath());
            TestProfiles.link(from, folder.resolve(paper.sha256() + ".pdf"));
        }

        ProfileReport report = reportFor(workspace.builder().syncAll(), "zz-copy");

        assertTrue(report.stored());
        assertEquals(0, report.embedded());
        assertEquals(source.papers().size(), report.reused());
    }

    /**
     * Each profile keeps the keywords its papers list: only keywords one of them lists, at most
     * {@value ProfileKeywords#MAX_KEYWORDS}, the one the most papers list first - and some for every
     * student, since every test student has papers that list keywords.
     */
    @Test
    public void eachProfileKeepsTheKeywordsItsPapersListMost() {
        for (Profile profile : TestProfiles.profiles().values()) {
            Map<String, Integer> lists = new HashMap<>();
            Set<String> titles = new HashSet<>();
            for (ProfilePaper paper : profile.papers()) {
                if (titles.add(Titles.compact(paper.title()))) {
                    paper.keywords().orElseThrow().stream().map(ProfileKeywords::key).distinct()
                            .forEach(key -> lists.merge(key, 1, Integer::sum));
                }
            }
            assumeTrue(profile.name() + " has no paper listing keywords", !lists.isEmpty());

            List<String> keywords = profile.keywords();
            assertFalse(profile.name(), keywords.isEmpty());
            assertTrue(profile.name() + ": " + keywords, keywords.size() <= ProfileKeywords.MAX_KEYWORDS);
            for (String keyword : keywords) {
                assertTrue(profile.name() + ": " + keyword, lists.containsKey(ProfileKeywords.key(keyword)));
            }
            assertEquals(profile.name() + ": " + keywords, Collections.max(lists.values()),
                    lists.get(ProfileKeywords.key(keywords.get(0))));
            assertEquals(keywords.size(), keywords.stream().map(ProfileKeywords::key).distinct().count());
        }
    }

    /**
     * A store saved before keywords were kept gets them on the next sync, read from the PDFs, with the
     * vectors and text reused rather than recomputed, and the same keywords as a fresh build.
     */
    @Test
    public void keywordsMissingFromAnOlderStoreAreAddedWithoutReembedding() throws IOException {
        Workspace workspace = TestProfiles.freshWorkspace();
        Map<String, Profile> saved = workspace.store().loadAll();
        Profile stored = saved.get(smallestProfile().name());
        List<ProfilePaper> withoutKeywords = stored.papers().stream()
                .map(p -> new ProfilePaper(p.relativePath(), p.sha256(), p.title(), p.embedding(), p.abstractView(),
                        p.structureView(), p.bodyView(), p.abstractText(), p.bodyText()))
                .toList();
        workspace.store().save(new Profile(stored.name(), stored.emails(), withoutKeywords, stored.modelId(),
                stored.updatedAt()));

        ProfileReport report = reportFor(workspace.builder().syncAll(), stored.name());

        assertEquals(0, report.embedded());
        assertTrue(report.textAdded() > 0);
        Profile updated = workspace.store().load(stored.name()).orElseThrow();
        assertEquals(stored.keywords(), updated.keywords());
        for (int i = 0; i < stored.papers().size(); i++) {
            ProfilePaper before = stored.papers().get(i);
            ProfilePaper after = updated.papers().get(i);
            assertEquals(before.keywords(), after.keywords());
            assertEquals(before.abstractText(), after.abstractText());
            assertArrayEquals(before.embedding(), after.embedding(), 0f);
        }
    }

    /**
     * A store saved before paper text was kept gets the text added from the PDFs on the next sync,
     * with the vectors reused rather than recomputed.
     */
    @Test
    public void textMissingFromAnOlderStoreIsAddedWithoutReembedding() throws IOException {
        Workspace workspace = TestProfiles.freshWorkspace();
        Map<String, Profile> saved = workspace.store().loadAll();
        Profile stored = saved.get(smallestProfile().name());
        List<ProfilePaper> vectorsOnly = stored.papers().stream()
                .map(p -> new ProfilePaper(p.relativePath(), p.sha256(), p.title(), p.embedding(), p.abstractView(),
                        p.structureView(), p.bodyView()))
                .toList();
        workspace.store().save(new Profile(stored.name(), stored.emails(), vectorsOnly, stored.modelId(), stored.updatedAt()));
        // A paper also saved in another folder may be known with its text from there, and need no reading.
        long elsewhere = vectorsOnly.stream().filter(paper -> saved.values().stream()
                .anyMatch(other -> other != stored && other.papers().stream().anyMatch(p -> p.sha256().equals(paper.sha256()))))
                .count();

        ProfileReport report = reportFor(workspace.builder().syncAll(), stored.name());

        assertEquals(0, report.embedded());
        assertTrue(report.textAdded() + " of " + vectorsOnly.size(),
                report.textAdded() >= vectorsOnly.size() - elsewhere && report.textAdded() <= vectorsOnly.size());
        Profile updated = workspace.store().load(stored.name()).orElseThrow();
        for (int i = 0; i < vectorsOnly.size(); i++) {
            assertTrue(updated.papers().get(i).relativePath(), updated.papers().get(i).hasText());
            assertArrayEquals(vectorsOnly.get(i).embedding(), updated.papers().get(i).embedding(), 0f);
        }
    }

    @Test
    public void vectorsFromAnotherModelAreRecomputed() throws IOException {
        Profile source = smallestProfile();
        List<ProfilePaper> papers = source.papers().subList(0, Math.min(2, source.papers().size()));
        Workspace workspace = TestProfiles.emptyWorkspace();
        Path folder = Files.createDirectories(workspace.root().resolve("zz-stale"));
        for (ProfilePaper paper : papers) {
            Path pdf = TestProfiles.source().resolve(source.name()).resolve(paper.relativePath());
            TestProfiles.link(pdf, folder.resolve(pdf.getFileName()));
        }
        // The same papers, saved as if an older model had embedded them.
        workspace.store().save(new Profile("zz-stale", List.of(), papers, "some-older-model", source.updatedAt()));

        ProfileReport report = reportFor(workspace.builder().syncAll(), "zz-stale");

        assertEquals(papers.size(), report.embedded());
        assertEquals(Specter2EmbeddingProvider.MODEL_ID, workspace.store().load("zz-stale").orElseThrow().modelId());
    }

    /** The papers as a whole: the mean of their combined vectors. */
    private static float[] mean(List<ProfilePaper> papers) {
        return Vectors.mean(papers.stream().map(ProfilePaper::embedding).toList());
    }

    private static Profile smallestProfile() {
        return TestProfiles.profiles().values().stream()
                .min((a, b) -> Integer.compare(a.papers().size(), b.papers().size())).orElseThrow();
    }

    private static ProfileReport reportFor(BuildReport report, String name) {
        return report.profiles().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.toString().length() - a.toString().length()).toList()) {
                Files.delete(path);
            }
        }
    }
}
