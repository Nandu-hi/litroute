package com.litroute.scoring.ai;

import com.litroute.model.Profile;
import com.litroute.model.Profile.ProfilePaper;
import com.litroute.util.Vectors;
import java.util.Comparator;
import java.util.List;

/**
 * What the language model is told about one student: the profile's name, its keywords, and the titles of
 * the student's most representative reference papers.
 *
 * @param name        the profile's name, exactly as M7d stores it; the model answers with it
 * @param keywords    the student's research keywords, most telling first (M5 keeps them with the
 *                    profile); empty if none of the student's papers lists keywords
 * @param paperTitles titles of the student's reference papers, each once
 */
public record ProfileBrief(String name, List<String> keywords, List<String> paperTitles) {

    /**
     * Titles per student in a prompt. Eight students with all of their ~300 papers' titles made a prompt
     * of 61,000 characters; the six papers closest to the student's work as a whole say nearly as much.
     */
    public static final int MAX_TITLES = 6;

    /**
     * Input: name (non-blank), keywords and paper titles.
     * Output: the brief, with immutable copies of the lists; throws on a blank name.
     * Used by: {@link #from}; tests.
     * Example: {@code new ProfileBrief("alice", List.of("multi-hazard"), List.of("Wave loads on piles"))}
     */
    public ProfileBrief {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        keywords = List.copyOf(keywords);
        paperTitles = List.copyOf(paperTitles);
    }

    /**
     * The brief for a stored profile: the keywords M5 kept for it, and the titles of the
     * {@value #MAX_TITLES} papers whose SPECTER2 vectors are closest to the mean of the student's papers -
     * the most typical of their work. Blank and repeated titles are skipped.
     * Input: a stored profile (M7d).
     * Output: its name, keywords and the titles of its {@value #MAX_TITLES} papers closest to the profile mean.
     * Used by: AiPaperMatcher.of (M6 AI).
     * Example: alice with 40 papers -> ProfileBrief(alice, [multi-hazard, ...], 6 most typical titles)
     */
    public static ProfileBrief from(Profile profile) {
        float[] centroid = Vectors.normalize(Vectors.mean(profile.papers().stream().map(ProfilePaper::embedding).toList()));
        List<String> titles = profile.papers().stream()
                .sorted(Comparator.comparingDouble((ProfilePaper paper) -> Vectors.cosine(paper.embedding(), centroid))
                        .reversed())
                .map(paper -> MatchPrompt.oneLine(paper.title()))
                .filter(title -> !title.isEmpty())
                .distinct()
                .limit(MAX_TITLES)
                .toList();
        return new ProfileBrief(profile.name(), profile.keywords(), titles);
    }
}
