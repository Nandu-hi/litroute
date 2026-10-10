package com.litroute.profile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.litroute.model.Profile;
import com.litroute.model.Profile.InterestCluster;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.testing.TestProfiles;
import com.litroute.util.Vectors;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Clusters the real paper embeddings of the test profiles. */
public class EmbeddingClustererTest {

    private final EmbeddingClusterer clusterer = new EmbeddingClusterer();

    @Test
    public void everyPaperBelongsToExactlyOneInterest() {
        for (Profile profile : TestProfiles.profiles().values()) {
            List<InterestCluster> clusters = clusterer.cluster(profile.papers());

            List<String> members = new ArrayList<>();
            clusters.forEach(c -> members.addAll(c.memberSha256s()));
            Set<String> papers = new HashSet<>();
            profile.papers().forEach(p -> papers.add(p.sha256()));
            assertEquals(profile.name(), papers.size(), members.size());
            assertEquals(profile.name(), papers, new HashSet<>(members));
            for (InterestCluster cluster : clusters) {
                assertEquals(1.0, Vectors.norm(cluster.centroid()), 1e-5);
            }
        }
    }

    @Test
    public void builderStoresTheSameClustersTheClustererProduces() {
        for (Profile profile : TestProfiles.profiles().values()) {
            List<InterestCluster> fresh = clusterer.cluster(profile.papers());

            assertEquals(fresh.size(), profile.clusters().size());
            for (int i = 0; i < fresh.size(); i++) {
                assertEquals(fresh.get(i).memberSha256s(), profile.clusters().get(i).memberSha256s());
            }
        }
    }

    @Test
    public void singlePaperIsItsOwnInterest() {
        ProfilePaper paper = anyPaper();

        List<InterestCluster> clusters = clusterer.cluster(List.of(paper));

        assertEquals(1, clusters.size());
        assertEquals(List.of(paper.sha256()), clusters.get(0).memberSha256s());
        assertArrayEquals(paper.embedding(), clusters.get(0).centroid(), 1e-6f);
    }

    @Test
    public void theSamePaperTwiceLandsInOneInterest() {
        ProfilePaper paper = anyPaper();
        ProfilePaper copy = new ProfilePaper("copy.pdf", "copy-" + paper.sha256(), paper.title(), paper.embedding(),
                paper.abstractView(), paper.structureView(), paper.bodyView());

        List<InterestCluster> clusters = clusterer.cluster(List.of(paper, copy));

        assertEquals(1, clusters.size());
    }

    @Test
    public void thresholdControlsHowFarPapersMerge() {
        List<ProfilePaper> papers = new ArrayList<>();
        TestProfiles.profiles().values().forEach(p -> papers.addAll(p.papers()));

        assertEquals(papers.size(), new EmbeddingClusterer(1.01).cluster(papers).size());
        assertEquals(1, new EmbeddingClusterer(-1.0).cluster(papers).size());
    }

    private static ProfilePaper anyPaper() {
        return TestProfiles.profiles().values().iterator().next().papers().get(0);
    }
}
