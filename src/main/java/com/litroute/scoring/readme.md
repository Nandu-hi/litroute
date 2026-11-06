# `scoring` (M6): match each paper to at most one student

M6 compares each deduplicated paper with every student's reference papers in two ways: by meaning (SPECTER2) and by wording (TF-IDF). It gives the paper to the **one** student it is most relevant to, and only when the paper is close to that student's own work. There is no quota per student.

- **In:** profiles from M7d (built with the same model; profiles from another model are rejected), the batch (papers from M7c, or local PDFs via [LocalPdfCandidates](LocalPdfCandidates.java)), SPECTER2, and the authors list.
- **Out:** `Map<student, List<ScoredPaper>>`, with every student in profile order. Papers by listed authors come first, then the rest best first. [matchAll](RelevanceScorer.java) also returns each paper that wasn't matched, as `UNMATCHED` under its closest student, with the reason at the start of its rationale. Every paper carries every student's fit, which the Review tab's **Move** uses.

## Rules

| | Abstract or PDF | Title only |
|---|---|---|
| Match floor (relevance) | **0.85** | **0.6** |
| Floor for a listed author | 0.5 | 0.36 |

- **Research papers only.** Notes (book reviews, discussions, errata, special-issue introductions) never match ([PaperCandidate.isResearch](../model/PaperCandidate.java); M2 already drops them).
- **Evidence:** a readable PDF if there is one, then title + abstract, then the title alone. An abstract under 25 words counts as none, because alerts put link text or a one-line snippet in that spot.
- **Precision over recall:** the floors were raised from 0.7 / 0.43 to 0.85 / 0.6 in November 2026. On the benchmark that took precision from 67% to 94% and recall from 88% to 62%. See [measurements.md](measurements.md).
- **Listed authors** ([AuthorsPane](../ui/AuthorsPane.java)): a paper whose author is on the list gets the lower floor, is listed first, and its rationale names the author. Names are matched strictly ([PersonNames](../util/PersonNames.java)): Alice Carter matches Alice K. Carter but not A. Carter. The alert's own author list is used, so an author it prints only as an initial goes unnoticed.

## How a paper is scored

Full formulas, with line references: [calc.md](calc.md).

1. **Meaning:** `½·cos(c, centroid) + ½·mean cos(c, 3 closest papers)`. A PDF candidate uses the papers' combined vectors. Any other candidate uses their abstract views. Duplicate reference papers count once.
2. **Wording:** the same ½ + ½ blend over TF-IDF vectors ([LexicalIndex](LexicalIndex.java), [Terms](Terms.java): stems plus adjacent stem pairs; a term must appear in at least 2 papers and at most half of them).
3. **Fit:** each signal is z-scored across the students, then the two are averaged, so a generic paper stands out for no one.
4. **Share:** `softmax(2·fit)`, how much of the paper belongs to each student.
5. **Relevance:** where the paper's similarity falls between the `other` and `own` anchors, put through a logistic curve. 0.9 ≈ as close as the student's own papers; 0.1 ≈ as close as another student's. The anchors are medians found by holding out each reference paper in turn, per kind of evidence.
   - A PDF with an abstract: ½ whole text + ½ title and abstract.
   - Title only: meaning weighted 0.65, wording 0.35.
   - Title lift: if the paper looks closer to a student from its title alone, relevance rises by 0.6 of the gap. It is never lowered.
6. **Score** = relevance × share. The rationale gives the share and the runner-up, the relevance, the shared words, the closest papers and the evidence used.

`Strategy.HYBRID` (the default) uses both signals. `SEMANTIC` and `LEXICAL` exist for comparison; `HYBRID` refuses a profile saved without text. `-Dlitroute.semantic=abstract` (experimental, off by default) runs SPECTER2 on title + abstract only.

## Classes

| Class | Role |
|---|---|
| [PaperMatcher](PaperMatcher.java) | Interface: `match(batch)` → `Map<student, List<ScoredPaper>>` |
| [RelevanceScorer](RelevanceScorer.java) | The matcher: calibration, scoring, floors, rationale |
| [LexicalIndex](LexicalIndex.java), [Terms](Terms.java) | TF-IDF over the profile papers' text (package-private) |
| [LocalPdfCandidates](LocalPdfCandidates.java) | Wraps a PDF given by hand as a candidate |
| [MatchEvaluation](MatchEvaluation.java) | Correct assignments and P@3 against papers filed by student |
| [RelevanceScorerCli](RelevanceScorerCli.java) | No args: match M7c into M7e. With `PDF_OR_FOLDER`: print scores only; if the subfolders are named after students, also compare the strategies |

**Stores:** M6 only reads (M7d, plus the batch M9 passes it: M7c papers that are neither queued nor sent). M9 writes the result to M7e. The one exception is `RelevanceScorerCli` with no args, which writes to M7e itself.

## Tests

All tests use real data: the real model, the real profiles, the papers filed by student in `~/.litroute/match-test`, and the client's alert emails. [RelevanceScorerTest](../../../../../test/java/com/litroute/scoring/RelevanceScorerTest.java) checks:

- each paper goes to at most one student, and only above the floor;
- of the filed papers, ≥ 85% reach their student from the PDF and ≥ 80% from title + abstract, with ≥ 95% of matches right;
- P@3 ≥ 0.8;
- hybrid beats SPECTER2 alone;
- short abstracts are treated as titles;
- listed authors get more matches without losing any.

[LexicalIndexTest](../../../../../test/java/com/litroute/scoring/LexicalIndexTest.java) and [TermsTest](../../../../../test/java/com/litroute/scoring/TermsTest.java) cover the wording side. For end-to-end numbers, see the [benchmark](../../../../../../benchmark/README.md).
