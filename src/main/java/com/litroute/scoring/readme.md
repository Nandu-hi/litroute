# `scoring` (M6): match each paper to at most one student

Compares each paper with every student's reference papers by meaning (SPECTER2) and wording (TF-IDF). The paper goes to the **one** student it is most relevant to, and only if it is close to that student's own work. There is no quota.

- **In:** profiles (M7d, same model), the batch (M7c papers neither queued nor sent), the authors list.
- **Out:** student → matched papers (listed authors first, then best first). `matchAll` also returns every unmatched paper as `UNMATCHED` under its closest student, with the reason.

| Match floor (relevance) | Abstract or PDF | Title only |
|---|---|---|
| Anyone | **0.85** | **0.6** |
| Listed author | 0.5 | 0.36 |

Evidence: a readable PDF, else title + abstract, else title. An abstract under 25 words counts as none. The floors favour precision: 94% precision, 62% recall on the benchmark ([measurements.md](measurements.md)).

## Scoring ([calc.md](calc.md) has the formulas)

1. **Meaning:** ½·cos(paper, centroid) + ½·mean cos(paper, 3 closest papers).
2. **Wording:** the same blend over TF-IDF ([LexicalIndex](LexicalIndex.java), [Terms](Terms.java)).
3. **Fit:** each signal z-scored across students, then averaged.
4. **Share:** `softmax(2·fit)`.
5. **Relevance:** logistic position between the "other student" and "own paper" anchors (0.1 / 0.9), calibrated on held-out profile papers.
6. **Score** = relevance × share, with a rationale.

| Class | Role |
|---|---|
| [PaperMatcher](PaperMatcher.java) | `match(batch)`, `matchAll(batch)` |
| [RelevanceScorer](RelevanceScorer.java) | Calibration, scoring, floors, rationale (`HYBRID`; `SEMANTIC`/`LEXICAL` for comparison) |
| [LocalPdfCandidates](LocalPdfCandidates.java) | A PDF on disk as a candidate |
| [MatchEvaluation](MatchEvaluation.java) | Accuracy and P@3 against papers filed by student |
| [RelevanceScorerCli](RelevanceScorerCli.java) | Match M7c into M7e, or score given PDFs |

**Tests** (real model, profiles and alerts): [RelevanceScorerTest](../../../../../test/java/com/litroute/scoring/RelevanceScorerTest.java) checks at most one student per paper, the floors, ≥ 95% of matches right, P@3 ≥ 0.8, and hybrid beating SPECTER2 alone. [LexicalIndexTest](../../../../../test/java/com/litroute/scoring/LexicalIndexTest.java) and [TermsTest](../../../../../test/java/com/litroute/scoring/TermsTest.java) cover the wording side. End to end: the [benchmark](../../../../../../benchmark/README.md).
