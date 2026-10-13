# M6 scoring: the calculations

**Notation.** c is the candidate paper, and s is one of the S students. A student's papers p₁ … pₙ are their **distinct** reference papers: copies of one paper (same SHA-256, or cosine ≥ 0.99) count once ([sameAs](RelevanceScorer.java#L612)). All SPECTER2 vectors have unit length.

## 1. Meaning similarity (SPECTER2)

Vectors compared:
- a candidate scored from its **PDF** uses its combined vector, against the papers' combined vectors;
- one scored from **title + abstract**, or **title** alone, uses that embedding, against the papers' abstract views.

These are the papers' vectors v₁ … vₙ in the formulas below.

- `centroid_s = normalize((v₁ + … + vₙ) / n)` ([line 662](RelevanceScorer.java#L662))
- `top3_s = mean of the 3 largest cos(c, vᵢ)`, or of all of them if n < 3 ([lines 679–680](RelevanceScorer.java#L679))
- `sem_s = 0.5 · cos(c, centroid_s) + 0.5 · top3_s` ([line 681](RelevanceScorer.java#L681))
- `sem_s = 0` if the student has no papers ([line 672](RelevanceScorer.java#L672))

## 2. Wording similarity (TF-IDF)

Terms are stems and adjacent-stem pairs ([Terms](Terms.java)). N is the number of reference papers, and df(t) is how many of them contain term t.

- A term is kept only if `2 ≤ df(t) ≤ 0.5 · N` ([line 67](LexicalIndex.java#L67))
- `idf(t) = ln((N + 1) / (df(t) + 1)) + 1` ([line 68](LexicalIndex.java#L68))
- `w(t) = (1 + ln count(t)) · idf(t)`, then the vector is scaled to unit length ([lines 168–175](LexicalIndex.java#L168))
- `cos(a, b) = Σₜ a(t) · b(t)` ([line 185](LexicalIndex.java#L185))
- With x the candidate's TF-IDF vector and d₁ … dₙ the student's papers' vectors:
  `together_s = Σᵢ cos(x, dᵢ) / √(Σᵢ Σⱼ cos(dᵢ, dⱼ))`, which equals `cos(x, d₁ + … + dₙ)` ([lines 128–136](LexicalIndex.java#L128))
- `lex_s = 0.5 · together_s + 0.5 · mean of the 3 largest cos(x, dᵢ)` ([lines 143–144](LexicalIndex.java#L143))

## 3. Fit (z-scores across the students)

For each signal, with values x₁ … x_S (the sem or lex values of the S students) ([zScores](RelevanceScorer.java#L419)):

- `mean = (x₁ + … + x_S) / S` ([line 422](RelevanceScorer.java#L422))
- `sd = √( Σₛ (xₛ − mean)² / S )` ([lines 426–428](RelevanceScorer.java#L426))
- `zₛ = (xₛ − mean) / sd`; every z is 0 if sd < 1e-12 ([lines 430–432](RelevanceScorer.java#L430))
- `fit_s = (z_s(sem) + z_s(lex)) / 2` ([line 413](RelevanceScorer.java#L413))

## 4. Share (softmax)

- `share_s = exp(2 · (fit_s − max fit)) / Σₜ exp(2 · (fit_t − max fit))` ([lines 446–450](RelevanceScorer.java#L446))
- The shares sum to 1. Subtracting `max fit` ([line 441](RelevanceScorer.java#L441)) doesn't change them; it only keeps `exp` from overflowing.

## 5. Relevance

**Anchors**, measured once per signal and per kind of evidence (PDF, title + abstract, title) ([calibrate](RelevanceScorer.java#L554)):
- Each distinct reference paper is held out and scored like a candidate with that evidence against every student; its own student is scored without it ([line 573](RelevanceScorer.java#L573)).
- `own` is the median similarity to its own student, and `other` the median similarity to the other students ([lines 596–608](RelevanceScorer.java#L596)). For an even count the median is the upper of the two middle values.
- If they can't be measured, or `own ≤ other`, fixed fallback anchors are used ([anchors](RelevanceScorer.java#L592)).

**Per candidate:**
- `n(sem) = (sem_s − other) / (own − other)`, and the same for lex ([line 165](RelevanceScorer.java#L165))
- `n = (n(sem) + n(lex)) / 2` ([lines 459–466](RelevanceScorer.java#L459))
- `relevance_s = 1 / (1 + e^(−ln 9 · (2n − 1)))` ([line 169](RelevanceScorer.java#L169)). This gives 0.1 at n = 0 (like another student's paper), 0.5 at n = 0.5 and 0.9 at n = 1 (like the student's own).

## 6. Score, assignment, recommendation

- `score_s = relevance_s · share_s` ([line 361](RelevanceScorer.java#L361))
- **Assigned student** = the one with the highest score ([line 364](RelevanceScorer.java#L364), [line 294](RelevanceScorer.java#L294))
- **Recommended** if all of these hold:
  - it is a research paper ([isResearch](RelevanceScorer.java#L327));
  - `relevance ≥ 0.5` ([line 302](RelevanceScorer.java#L302));
  - it is among the student's 3 highest-scoring papers in the batch ([lines 311–314](RelevanceScorer.java#L311)).

With `SEMANTIC` or `LEXICAL` alone, steps 3 and 5 use just that one signal.

## Constants

| Constant | Value | Used in |
|---|---|---|
| [PROFILE_WEIGHT](RelevanceScorer.java#L79) | 0.5 | centroid / closest-papers blend (sections 1, 2) |
| [CLOSEST_PAPERS](RelevanceScorer.java#L80) | 3 | top-3 closest papers (sections 1, 2) |
| [SAME_PAPER](RelevanceScorer.java#L82) | 0.99 | cosine at which two papers count as one |
| [MIN_PAPERS](LexicalIndex.java#L27) / [MAX_PAPER_SHARE](LexicalIndex.java#L28) | 2 / 0.5 | TF-IDF term filter (section 2) |
| [SHARE_SHARPNESS](RelevanceScorer.java#L88) | 2 | softmax (section 4) |
| [LOG_9](RelevanceScorer.java#L120) | ln 9 | relevance curve (section 5) |
| [RELEVANCE_FLOOR](RelevanceScorer.java#L97) | 0.5 | recommendation floor (section 6) |
| [RECOMMENDED_PER_STUDENT](RelevanceScorer.java#L77) | 3 | recommendations per student (section 6) |
