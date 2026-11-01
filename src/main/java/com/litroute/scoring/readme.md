# The `scoring` module (M6)

Matches each deduplicated paper to the **one** student it suits best, and only if it is a research paper close enough to that student's own work (relevance ≥ 0.7, or 0.43 from a title alone; for a paper by an author on the professor's list, 0.5 or 0.36); there is no quota per student, and every paper it returns is recommended. A paper is compared with every student's reference papers by meaning (SPECTER2) and by wording (TF-IDF). Design decisions and measurements: [m6plan.txt](../../../../../../m6plan.txt).

## Input and output

- **In:**
  - every student's [Profile](../model/Profile.java) from M7d: their papers' vectors and text, built with the same model (other models are rejected);
  - the batch: deduplicated [PaperCandidate](../model/PaperCandidate.java)s from M7c, or local PDFs via [LocalPdfCandidates](LocalPdfCandidates.java);
  - SPECTER2 ([EmbeddingProvider](../embedding/EmbeddingProvider.java));
  - the authors list, as given to the constructor ([PriorityAuthorStore](../store/PriorityAuthorStore.java) holds it).
- **Out:** `Map<profile name, List<ScoredPaper>>` ([PaperMatcher.match](PaperMatcher.java)): every student, in profile order, with the papers assigned to them, highest score first (an empty list if none). Each [ScoredPaper](../model/ScoredPaper.java) has a score from 0 to 1, a rationale, `PENDING` status, a `recommended` flag and, if it was written by a listed author, that author's name as printed. A paper appears in at most one list; one close to no student is not returned at all. M9 (or the CLI) queues them in M7e, which copies each matched PDF into the student's folder.

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
  match(batch):  best student per paper ─▶ keep it only if it is a research paper
                 with relevance ≥ 0.7; no quota per student, the rest match no one
                                │
                                ▼
       Map<student, List<ScoredPaper>> ──▶ ScoredQueueStore.addAll (M7e) ──▶ M8 review
                                              └─ PDFs copied to matched/<student>/
```

## How a paper is scored

1. **Evidence** ([read](RelevanceScorer.java#L741)): its PDF if readable, else title + abstract, else title alone. A PDF is compared with the papers' combined vectors, the rest with their abstract views.
2. **Meaning** ([Space.closeness](RelevanceScorer.java#L981)), for each student:
   ```
   closeness = ½ · cos(c, centroid) + ½ · mean cos(c, the student's 3 closest papers)
   centroid  = normalize(mean of the student's paper vectors)
   ```
   - Copies of a paper (same hash, or cosine ≥ 0.99) count once ([sameAs](RelevanceScorer.java#L887)).
   - The centroid is computed on [line 961](RelevanceScorer.java#L961) and the blend on [line 992](RelevanceScorer.java#L992).
   - Paper vectors are unit length, so cos(c, centroid) is the average similarity to all the student's papers divided by |mean|: fit with their work as a whole. The closest-papers half rewards matching one part of it.
3. **Wording** ([LexicalIndex.closeness](LexicalIndex.java#L142)): the same ½ + ½ blend over TF-IDF vectors.
4. **Fit** ([fit](RelevanceScorer.java#L562)): each signal is standardized across the students, then the two are averaged, so a generic paper stands out for no one. For one signal, with similarities x₁ … x_S for the S students ([zScores](RelevanceScorer.java#L580)):
   - `mean = (x₁ + … + x_S) / S` ([line 583](RelevanceScorer.java#L583))
   - `sd = √( ((x₁ − mean)² + … + (x_S − mean)²) / S )`, dividing by S, not S − 1 ([lines 587–589](RelevanceScorer.java#L587))
   - `z_s = (x_s − mean) / sd`; if sd is (near) 0, below 1e-12, every z is 0, since the signal can't tell the students apart ([lines 591–595](RelevanceScorer.java#L591))
   - `fit_s = (z_s of meaning + z_s of wording) / 2`; `SEMANTIC` or `LEXICAL` alone uses that one signal's z ([line 568](RelevanceScorer.java#L568))
5. **Share** ([softmax](RelevanceScorer.java#L605)): how much of the paper belongs to each student, with sharpness k = 2 ([SHARE_SHARPNESS](RelevanceScorer.java#L91)):
   - `share_s = exp(k · (fit_s − max fit)) / Σₜ exp(k · (fit_t − max fit))` ([lines 613–617](RelevanceScorer.java#L613))
   - Subtracting the largest fit ([line 608](RelevanceScorer.java#L608)) leaves the shares unchanged and keeps `exp` from overflowing. The shares sum to 1; a larger k gives the best student more of the share.
6. **Relevance** ([relevance](RelevanceScorer.java#L650), [Anchors](RelevanceScorer.java#L213)): each similarity is placed between an `other` and an `own` anchor and passed through a logistic curve, so it is about 0.9 for a typical own paper and 0.1 for another student's. The anchors are medians measured by holding out each reference paper in turn, per kind of evidence ([calibrate](RelevanceScorer.java#L810)).
7. **Score** = relevance × share ([line 505](RelevanceScorer.java#L505)). The rationale gives the share and runner-up, the relevance, the shared words, the closest papers and the evidence used.

## Assigning and recommending

[match](RelevanceScorer.java#L411) gives each paper to its highest-scoring student, but only if it is a research paper (not a book review, discussion, erratum and the like; [PaperCandidate.isResearch](../model/PaperCandidate.java), which M2 already applies) with relevance ≥ 0.7 ([RELEVANCE_FLOOR](RelevanceScorer.java#L103)). Any other paper matches no one and is not queued. There is no quota per student: a student gets every paper that qualifies, however many or few.

**Why 0.7 and no quota** (October 2026, the professor's first reviewed batch of 31 alert papers): with the old rule every paper was queued and each student's top 3 above 0.5 recommended, so 20 of the 31 queued papers were dropped, 15 of them landing on RP4-mh-char, the broadest hazard profile. Five were book reviews, a discussion, a closure and an erratum (one book review scored relevance 0.96); nine were below 0.5. Above 0.5, six of the 17 research papers were generic hazard or disaster-management papers the professor dropped, and the quota of 3 had cut a rainstorm hazard-chain paper (relevance 0.91) from RP4. Raising the floor while queuing only what clears it:

| Rule | Queued | Approved by the professor | Dropped by the professor |
|---|---|---|---|
| old: everything, top 3 per student ★ | 31 | 11 (all ★) | 20 |
| research, relevance ≥ 0.5 | 17 | 11 | 6 |
| research, relevance ≥ 0.6 | 15 | 10 | 5 |
| research, relevance ≥ 0.7 | 11 | 9 (+ the hazard-chain paper) | 1 |
| research, relevance ≥ 0.75 | 9 | 8 (+ the hazard-chain paper) | 0 |

On the papers the professor filed by student, 0.7 still matches 41 of 46 to their student from a full PDF or from a title and abstract (43 at 0.5), and 33 of 46 from a title alone (35 at 0.5). 0.75 would buy one fewer wrong paper in the alerts for a lower recall on everything, so 0.7 was chosen.

**Titles weigh more** (October 2026, after the professor ruled that three papers left out may not be missed: the flood fragility of bridge piers for RP1 and the compound geohazards of the Greater Bay Area for RP4, both known by title only because Elsevier deposits no abstracts, and the ground-motion orientation paper for RP8, whose title says more than its abstract):

- **Title lift** ([TITLE_LIFT](RelevanceScorer.java) = 0.6): a paper with an abstract or PDF is also scored from its title alone; when the title looks closer to a student, the relevance rises by 0.6 of the gap, and it is never lowered. Ground-motion orientation: 0.59 from its abstract, 0.85 from its title, 0.70 lifted. 0.45 would leave it at 0.68.
- **Titles weigh meaning above words** ([TITLE_MEANING_WEIGHT](RelevanceScorer.java) = 0.65): a dozen words share few terms with anyone's papers.
- **Title-only floor** ([TITLE_FLOOR](RelevanceScorer.java) = 0.43): a title's relevance stays near the middle of the scale even when it plainly belongs to a student. Bridge piers 0.61 and compound geohazards 0.44 are in; the out-of-scope review of steels under impact, 0.41, is out.

On the batch: 16 of 26 matched, the 14 the professor wants plus the flood cost–benefit and drought–flood papers (one out of scope, one borderline). On the professor's filed papers known by title and abstract, no match changes; judged by title alone, 34 of 46 reach their student (32 before) and 5 reach another (3 before).

**Result** on the 48 papers the professor filed by student, from full PDFs: `HYBRID` (the default) assigns 46 correctly and gets every top 3 right. `SEMANTIC` alone gets 43 and P@3 0.81; `LEXICAL` alone gets 43 and 0.88.

## Authors on the professor's list

A paper by an author on the list ([AuthorsPane](../ui/AuthorsPane.java)) is scored like any other and goes to the same student. Only three things change in [match](RelevanceScorer.java):

- **A lower floor.** Relevance ≥ 0.5 ([AUTHOR_FLOOR](RelevanceScorer.java)), or ≥ 0.36 from a title alone ([AUTHOR_TITLE_FLOOR](RelevanceScorer.java)). Below that the paper fits no one's work and is ignored, whoever wrote it. It must still be a research paper.
- **First in the list.** It comes before the student's other papers here and in the Review tab, however low its score.
- **The author as a reason.** Its rationale opens with the author, as printed and as listed: "Matched because Alice K. Carter (listed as Alice Carter) wrote it, an author on your list: its relevance 0.55 reaches their floor of 0.50, below the usual 0.70." A paper that would have matched anyway says "By an author on your list: …".

The list holds first and last names ([AuthorName](../model/AuthorName.java)), compared strictly by [PersonNames.matches](../util/PersonNames.java): Alice Carter finds Alice Carter and Alice K. Carter, but not A. Carter or AK Carter. Only the authors M2/M3 hold for a paper can be recognized, and an alert's own author list is kept over the one Crossref or OpenAlex give: an author the alert leaves out, or prints with an initial, goes unnoticed even if the lookup spells out their name.

**Why these floors** (November 2026). Relevance of the papers the two floors separate, as each paper's best student sees it, if a listed author had written each one:

| Evidence | Let in | Left out |
|---|---|---|
| Abstract or PDF, floor 0.5 | rural recovery barriers 0.64, landfill flowslides 0.56, seismic retrofitting adoption 0.55, coastal flooding decisions 0.51 | population outmigration 0.46, landslide detection 0.40, seismic stability of landfills 0.31, the client's irrelevant ceramic coating paper 0.29, post-disaster education 0.09 |
| Title alone, floor 0.36 | steels under impact 0.41 | judged by title alone: the ceramic coating paper 0.34, and all 32 arXiv papers below |

Of 32 unrelated arXiv papers (`~/.litroute/Test`: spinors, image enhancement, market efficiency and so on), none reaches 0.5 from its abstract (the highest is 0.43) and none reaches 0.36 from its title (the highest is 0.35). From a full PDF, one reaches 0.5 (quasi-kernels in graphs, 0.64), close to the rank aggregation profile's combinatorics. A paper needs a listed author as well, so a false match takes both. If every one of the 26 held papers had a listed author, 21 would match instead of 16.

These floors rest on few papers, and the title floor especially so: only five held papers are known by their title alone. A title can't do better, either: the SSI review the professor called highly relevant reaches only 0.24 from its title, below the papers that don't belong. Its PDF gives it 0.80. Re-measure as the professor reviews listed authors' papers.

## TF-IDF

- [Terms](Terms.java): lowercase, accent-folded stems plus pairs of adjacent stems (`rank_aggregation`). Words under 3 letters, function words and boilerplate are dropped, and a dropped word breaks a pair.
- [LexicalIndex](LexicalIndex.java#L57): `idf = ln((N+1)/(df+1)) + 1` ([line 73](LexicalIndex.java#L73)); each term is weighted `(1 + ln(count)) × idf` ([weigh](LexicalIndex.java#L201)); terms in only one paper or in more than half of them are dropped; vectors are unit length.

## The classes

| Class | Role |
|---|---|
| [PaperMatcher](PaperMatcher.java) | M6's interface: `match(batch)` → `Map<student, List<ScoredPaper>>`, which M9 and M7e take as it is. |
| [RelevanceScorer](RelevanceScorer.java) | The matcher: SPECTER2 meaning + TF-IDF wording, calibrated on the profiles, one student per paper, floors 0.7 / 0.43. |
| [LexicalIndex](LexicalIndex.java) | Package-private. The TF-IDF index over every profile paper's text; closeness and shared terms for a candidate's text. |
| [Terms](Terms.java) | Package-private. Text to term counts: folded stems and adjacent stem pairs, stopwords dropped. |
| [LocalPdfCandidates](LocalPdfCandidates.java) | A PDF dropped in by hand, as a candidate. |
| [MatchEvaluation](MatchEvaluation.java) | Package-private. Papers assigned correctly, and per-student P@3, against the professor's filing. |
| [RelevanceScorerCli](RelevanceScorerCli.java) | Terminal entry point: match M7c into M7e, or score PDFs and compare the strategies. |

## Interaction with M7 (the stores)

M6 reads two stores and writes none itself; M9 writes its result.

```
M7d profiles.json ──loadAll()──▶ RelevanceScorer(provider, profiles)   (vectors, text; modelId must match)
M7c enrichment_cache.json ──enriched, minus papers already scored──▶ match(batch)
                                                                        │ Map<student, List<ScoredPaper>>
                         sent_papers.json ── keys already sent ──▶ M9 filters them out
                                                                        ▼
                         M7e scored_queue.json ◀── addAll ── M9 (matched PDFs copied to matched/<student>/)
                         scored_papers.json   ◀── addAll(paperKeys) ── M9, after the queue
```

- **M7d → M6:** a profile saved by another model is rejected; one saved without text forces `SEMANTIC` only (and the default `HYBRID` refuses it).
- **M7c → M6:** M9 passes every deduplicated paper not yet in `scored_papers.json` or the queue, so papers left unscored by a failed run are scored by the next one.
- **M6 → M7e:** each `ScoredPaper` carries the candidate as M7c held it; M7e keys it by `queueKey()` and copies its `pdfPath` into the student's folder. Re-scoring a queued paper updates its score and rationale but keeps the professor's decision.
- **RelevanceScorerCli** is the exception that writes: without a path it reads M7c and M7d and calls `ScoredQueueStore.addAll(matches)` directly.

## Running it

```
mvn -q compile exec:java -Dexec.mainClass=com.litroute.scoring.RelevanceScorerCli
mvn -q compile exec:java -Dexec.mainClass=com.litroute.scoring.RelevanceScorerCli -Dexec.args=PDF_OR_FOLDER
```

- **Without a path:** matches M7c's papers and queues them in M7e.
- **With a PDF or folder:** prints the scores and changes nothing. If the subfolders are named after students (`FOLDER/RP3-mf-sim/x.pdf`), it also compares the three strategies.

## Tests

Real data only: the real model, the real profiles, the professor's filed papers and the client's real alert emails.

- [RelevanceScorerTest](../../../../../test/java/com/litroute/scoring/RelevanceScorerTest.java):
  - a paper goes to at most one student, its best, and only with relevance ≥ 0.7; every student is listed, in profile order, papers best first;
  - filed papers reach their student: ≥ 85% of the readable ones, from the PDF and from title + abstract alone, with ≥ 90% of matches right;
  - P@3 ≥ 0.8;
  - hybrid beats SPECTER2 alone;
  - every score is in 0–1 and explained;
  - blank, other-model and text-less profiles are handled;
  - from the client's alerts, the relevant SSI review is matched; the irrelevant ceramic-coating paper is not (M2 has already dropped the discussion, closure, book reviews and erratum);
  - with the alerts' authors listed, more papers are matched, none is lost, each says why, and the ceramic-coating paper still stays out; an author is found by first and last name, past a middle name, and another first name is someone else.
- [LexicalIndexTest](../../../../../test/java/com/litroute/scoring/LexicalIndexTest.java), [TermsTest](../../../../../test/java/com/litroute/scoring/TermsTest.java): held-out papers are closest to their own student; term folding, stems and pairs.
