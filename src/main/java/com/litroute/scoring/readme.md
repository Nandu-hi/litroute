# The `scoring` module (M6)

Matches each deduplicated paper to the **one** student it suits best, and recommends each student's best 3 papers of the batch. A paper is compared with every student's reference papers by meaning (SPECTER2) and by wording (TF-IDF). Design decisions and measurements: [m6plan.txt](../../../../../../m6plan.txt).

## Input and output

- **In:**
  - every student's [Profile](../model/Profile.java) from M7d: their papers' vectors and text, built with the same model (other models are rejected);
  - the batch: deduplicated [PaperCandidate](../model/PaperCandidate.java)s from M7c, or local PDFs via [LocalPdfCandidates](LocalPdfCandidates.java);
  - SPECTER2 ([EmbeddingProvider](../embedding/EmbeddingProvider.java)).
- **Out:** one [ScoredPaper](../model/ScoredPaper.java) per candidate, in batch order: the assigned student, a score from 0 to 1, a rationale, `PENDING` status and a `recommended` flag. Every paper is returned; one that isn't recommended says why. The CLI queues them in M7e.

## How it works

```
Profiles (M7d)                                   Candidate (M7c or a local PDF)
   │  RelevanceScorer constructor                   │  read(): best evidence available
   │   • Index per profile (duplicates removed):    │   ├─ readable PDF ─▶ FULL_TEXT: PdfTextExtractor + PaperEmbedder
   │       Space fullText  (combined vectors)       │   ├─ abstract     ─▶ ABSTRACT : embed(title, abstract)
   │       Space abstracts (abstract views)         │   └─ title only   ─▶ TITLE    : embed(title, "")
   │   • LexicalIndex over all papers' text         │        (cached per candidate)
   │   • Calibrate anchors per evidence type        │
   │     (hold out each paper, compare)             │
   ▼                                                ▼
                    assess(candidate)  ── for every student ──
   meaning:  Space.closeness   = ½·(profile centroid) + ½·(mean of 3 closest papers)
   wording:  LexicalIndex.closeness, blended the same way
        │
        ├─ fit       = average of z-scores across students     (generic papers stand out for no one)
        ├─ share     = softmax(2 · fit)                        (how much belongs to this student)
        ├─ relevance = logistic(own/other anchors)              (0.9 ≈ own papers, 0.1 ≈ others')
        └─ score     = relevance × share  + rationale
                                │
                                ▼
  match(batch):  best student per paper ─▶ per student, rank by score
                 recommend top N (default 3) that are research papers and have relevance ≥ 0.5
                                │
                                ▼
                     List<ScoredPaper> ──▶ ScoredQueueStore.addAll (M7e) ──▶ M8 review
```

## How a paper is scored

1. **Evidence** ([read](RelevanceScorer.java#L505)): its PDF if readable, else title + abstract, else title alone. A PDF is compared with the papers' combined vectors, the rest with their abstract views.
2. **Meaning** ([Space.closeness](RelevanceScorer.java#L670)), for each student:
   ```
   closeness = ½ · cos(c, centroid) + ½ · mean cos(c, the student's 3 closest papers)
   centroid  = normalize(mean of the student's paper vectors)
   ```
   - Copies of a paper (same hash, or cosine ≥ 0.99) count once ([sameAs](RelevanceScorer.java#L612)).
   - The centroid is computed on [line 662](RelevanceScorer.java#L662) and the blend on [line 681](RelevanceScorer.java#L681).
   - Paper vectors are unit length, so cos(c, centroid) is the average similarity to all the student's papers divided by |mean|: fit with their work as a whole. The closest-papers half rewards matching one part of it.
3. **Wording** ([LexicalIndex.closeness](LexicalIndex.java#L115)): the same ½ + ½ blend over TF-IDF vectors.
4. **Fit** ([fit](RelevanceScorer.java#L407)): each signal is standardized across the students, then the two are averaged, so a generic paper stands out for no one. For one signal, with similarities x₁ … x_S for the S students ([zScores](RelevanceScorer.java#L419)):
   - `mean = (x₁ + … + x_S) / S` ([line 422](RelevanceScorer.java#L422))
   - `sd = √( ((x₁ − mean)² + … + (x_S − mean)²) / S )`, dividing by S, not S − 1 ([lines 426–428](RelevanceScorer.java#L426))
   - `z_s = (x_s − mean) / sd`; if sd is (near) 0, below 1e-12, every z is 0, since the signal can't tell the students apart ([lines 430–432](RelevanceScorer.java#L430))
   - `fit_s = (z_s of meaning + z_s of wording) / 2`; `SEMANTIC` or `LEXICAL` alone uses that one signal's z ([line 413](RelevanceScorer.java#L413))
5. **Share** ([softmax](RelevanceScorer.java#L438)): how much of the paper belongs to each student, with sharpness k = 2 ([SHARE_SHARPNESS](RelevanceScorer.java#L88)):
   - `share_s = exp(k · (fit_s − max fit)) / Σₜ exp(k · (fit_t − max fit))` ([lines 446–450](RelevanceScorer.java#L446))
   - Subtracting the largest fit ([line 441](RelevanceScorer.java#L441)) leaves the shares unchanged and keeps `exp` from overflowing. The shares sum to 1; a larger k gives the best student more of the share.
6. **Relevance** ([relevance](RelevanceScorer.java#L455), [Anchors](RelevanceScorer.java#L164)): each similarity is placed between an `other` and an `own` anchor and passed through a logistic curve, so it is about 0.9 for a typical own paper and 0.1 for another student's. The anchors are medians measured by holding out each reference paper in turn, per kind of evidence ([calibrate](RelevanceScorer.java#L554)).
7. **Score** = relevance × share ([line 361](RelevanceScorer.java#L361)). The rationale gives the share and runner-up, the relevance, the shared words, the closest papers and the evidence used.

## Assigning and recommending

[match](RelevanceScorer.java#L289) gives each paper to its highest-scoring student. Each student's top 3 are recommended if they are research papers (not book reviews, discussions, errata and the like; [isResearch](RelevanceScorer.java#L327)) with relevance ≥ 0.5 ([RELEVANCE_FLOOR](RelevanceScorer.java#L97)).

**Result** on the 48 papers the professor filed by student, from full PDFs: `HYBRID` (the default) assigns 46 correctly and gets every top 3 right. `SEMANTIC` alone gets 43 and P@3 0.81; `LEXICAL` alone gets 43 and 0.88.

## TF-IDF

- [Terms](Terms.java): lowercase, accent-folded stems plus pairs of adjacent stems (`rank_aggregation`). Words under 3 letters, function words and boilerplate are dropped, and a dropped word breaks a pair.
- [LexicalIndex](LexicalIndex.java#L52): `idf = ln((N+1)/(df+1)) + 1` ([line 68](LexicalIndex.java#L68)); each term is weighted `(1 + ln(count)) × idf` ([weigh](LexicalIndex.java#L162)); terms in only one paper or in more than half of them are dropped; vectors are unit length.

## Other classes

- [LocalPdfCandidates](LocalPdfCandidates.java): a PDF dropped in by hand, as a candidate.
- [MatchEvaluation](MatchEvaluation.java): papers assigned correctly, and per-student P@3, against the professor's filing.

## Running it

```
mvn -q compile exec:java -Dexec.mainClass=com.litroute.scoring.RelevanceScorerCli [-Dexec.args=--top=2]
mvn -q compile exec:java -Dexec.mainClass=com.litroute.scoring.RelevanceScorerCli -Dexec.args="PDF_OR_FOLDER [--top=2]"
```

- **Without a path:** matches M7c's papers and queues them in M7e.
- **With a PDF or folder:** prints the scores and changes nothing. If the subfolders are named after students (`FOLDER/RP3-mf-sim/x.pdf`), it also compares the three strategies.

## Tests

Real data only: the real model, the real profiles, the professor's filed papers and the client's real alert emails.

- [RelevanceScorerTest](../../../../../test/java/com/litroute/scoring/RelevanceScorerTest.java):
  - each paper goes to one student;
  - filed papers reach their student: ≥ 95% of the readable ones, ≥ 90% from title + abstract alone;
  - P@3 ≥ 0.8, and at most 3 recommendations per student;
  - hybrid beats SPECTER2 alone;
  - every score is in 0–1 and explained;
  - blank, other-model and text-less profiles are handled;
  - from the client's alerts, only relevant research is recommended.
- [LexicalIndexTest](../../../../../test/java/com/litroute/scoring/LexicalIndexTest.java), [TermsTest](../../../../../test/java/com/litroute/scoring/TermsTest.java): held-out papers are closest to their own student; term folding, stems and pairs.
