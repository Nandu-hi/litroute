package com.litroute.profile;

import com.litroute.embedding.Specter2EmbeddingProvider;
import com.litroute.model.Profile;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.profile.ProfileBuilder.BuildReport;
import com.litroute.profile.ProfileBuilder.ProfileReport;
import com.litroute.profile.ProfileBuilder.SkippedPdf;
import com.litroute.store.ProfileStore;
import com.litroute.util.Vectors;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds or refreshes every profile from the profiles folder, until M9/M8 trigger it. Run after
 * adding, changing or removing a profile folder:
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=com.litroute.profile.ProfileBuilderCli [-Dexec.args=PROFILES_DIR]
 * </pre>
 *
 * PROFILES_DIR defaults to ~/.litroute/profiles. Besides what changed, it prints each profile's keywords,
 * how similar its papers are to one another, and how close each is to the profile as a whole (the mean
 * of its papers' vectors), so a paper that does not fit its student stands out.
 */
public final class ProfileBuilderCli {

    private ProfileBuilderCli() {
    }

    /**
     * Input: optional PROFILES_DIR (default ~/.litroute/profiles).
     * Output: prints per profile what was embedded/reused/skipped, its keywords and how well each paper fits; M7d updated.
     * Used by: the user, from the terminal ({@code exec:java -Dexec.mainClass=com.litroute.profile.ProfileBuilderCli}).
     * Example: no args, alice/ with 3 PDFs -> "alice: 3 embedded, 0 unchanged ..." then keywords and similarities
     */
    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : ProfileBuilder.defaultProfilesRoot();
        ProfileStore store = ProfileStore.atDefaultLocation();
        System.out.println("Profiles folder: " + root.toAbsolutePath());

        BuildReport report;
        try (Specter2EmbeddingProvider provider = Specter2EmbeddingProvider.atDefaultLocation()) {
            report = new ProfileBuilder(root, store, provider).syncAll();
        }

        Map<String, Profile> profiles = store.loadAll();
        if (report.profiles().isEmpty()) {
            System.out.println("No profile folders yet: create one folder per student here, holding their PDFs "
                    + "and optionally an " + ProfileBuilder.EMAIL_FILE + ".");
        }
        for (ProfileReport profileReport : report.profiles()) {
            System.out.printf("%n%s: %d embedded, %d unchanged (%d read again for text or keywords), %d skipped, emails %s%n",
                    profileReport.name(), profileReport.embedded(), profileReport.reused(), profileReport.textAdded(),
                    profileReport.skipped().size(), profileReport.emails());
            for (SkippedPdf skipped : profileReport.skipped()) {
                System.out.printf("  skipped %s: %s%n", skipped.relativePath(), skipped.reason());
            }
            if (profileReport.stored()) {
                Profile profile = profiles.get(profileReport.name());
                System.out.println("  keywords: " + (profile.keywords().isEmpty() ? "(none of its papers lists any)"
                        : String.join("; ", profile.keywords())));
                printPapers(profile);
            } else {
                System.out.println("  no readable PDF - profile not saved");
            }
        }
        for (String removed : report.removed()) {
            System.out.printf("%nRemoved profile %s (folder deleted)%n", removed);
        }
    }

    /**
     * Input: a stored profile.
     * Output: prints min/mean/max paper-to-paper cosine and each paper's cosine to the profile mean, best fit first.
     * Used by: {@link #main}.
     * Example: -> "paper-to-paper similarity: min 0.712, mean 0.801, max 0.903" ...
     */
    private static void printPapers(Profile profile) {
        var papers = profile.papers();
        double min = 1;
        double max = -1;
        double sum = 0;
        int pairs = 0;
        for (int i = 0; i < papers.size(); i++) {
            for (int j = i + 1; j < papers.size(); j++) {
                double similarity = Vectors.cosine(papers.get(i).embedding(), papers.get(j).embedding());
                min = Math.min(min, similarity);
                max = Math.max(max, similarity);
                sum += similarity;
                pairs++;
            }
        }
        if (pairs > 0) {
            System.out.printf("  paper-to-paper similarity: min %.3f, mean %.3f, max %.3f%n", min, sum / pairs, max);
        }
        float[] whole = Vectors.normalize(Vectors.mean(papers.stream().map(ProfilePaper::embedding).toList()));
        System.out.println("  similarity of each paper to the profile as a whole:");
        List<ProfilePaper> byFit = papers.stream()
                .sorted(Comparator.comparingDouble((ProfilePaper paper) -> Vectors.cosine(paper.embedding(), whole)).reversed())
                .toList();
        for (ProfilePaper paper : byFit) {
            System.out.printf("      %.3f  %s  (%s)%n", Vectors.cosine(paper.embedding(), whole), paper.title(),
                    paper.relativePath());
        }
    }
}
