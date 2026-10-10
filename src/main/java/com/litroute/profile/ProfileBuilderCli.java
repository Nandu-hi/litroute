package com.litroute.profile;

import com.litroute.embedding.Specter2EmbeddingProvider;
import com.litroute.model.Profile;
import com.litroute.model.Profile.InterestCluster;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.profile.ProfileBuilder.BuildReport;
import com.litroute.profile.ProfileBuilder.ProfileReport;
import com.litroute.profile.ProfileBuilder.SkippedPdf;
import com.litroute.store.ProfileStore;
import com.litroute.util.Vectors;
import java.nio.file.Path;
import java.util.Map;

/**
 * Builds or refreshes every profile from the profiles folder, until M9/M8 trigger it. Run after
 * adding, changing or removing a profile folder:
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=com.litroute.profile.ProfileBuilderCli [-Dexec.args=PROFILES_DIR]
 * </pre>
 *
 * PROFILES_DIR defaults to ~/.litroute/profiles. Besides what changed, it prints how similar each
 * profile's papers are to one another and how they were grouped into interests, which is what
 * {@link EmbeddingClusterer#MERGE_SIMILARITY} is tuned from.
 */
public final class ProfileBuilderCli {

    private ProfileBuilderCli() {
    }

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
            System.out.printf("%n%s: %d embedded, %d unchanged (%d given their text), %d skipped, emails %s%n",
                    profileReport.name(), profileReport.embedded(), profileReport.reused(), profileReport.textAdded(),
                    profileReport.skipped().size(), profileReport.emails());
            for (SkippedPdf skipped : profileReport.skipped()) {
                System.out.printf("  skipped %s: %s%n", skipped.relativePath(), skipped.reason());
            }
            if (profileReport.stored()) {
                printClusters(profiles.get(profileReport.name()));
            } else {
                System.out.println("  no readable PDF - profile not saved");
            }
        }
        for (String removed : report.removed()) {
            System.out.printf("%nRemoved profile %s (folder deleted)%n", removed);
        }
    }

    private static void printClusters(Profile profile) {
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
        System.out.printf("  %d interest(s) at merge threshold %.2f:%n", profile.clusters().size(),
                EmbeddingClusterer.MERGE_SIMILARITY);
        for (InterestCluster cluster : profile.clusters()) {
            System.out.println("  -");
            for (String sha256 : cluster.memberSha256s()) {
                ProfilePaper paper = papers.stream().filter(p -> p.sha256().equals(sha256)).findFirst().orElseThrow();
                System.out.printf("      %.3f  %s  (%s)%n", Vectors.cosine(paper.embedding(), cluster.centroid()),
                        paper.title(), paper.relativePath());
            }
        }
    }
}
