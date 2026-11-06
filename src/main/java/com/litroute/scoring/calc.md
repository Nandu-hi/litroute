# M6 scoring: the calculations

**Notation.** c is the candidate paper, and s is one of the S students. A student's papers p₁ … pₙ are their **distinct** reference papers: copies of one paper (same SHA-256, or cosine ≥ 0.99) count once ([sameAs](RelevanceScorer.java#L1065)). All SPECTER2 vectors have unit length.

## 1. Meaning similarity (SPECTER2)

Vectors compared:
- a candidate scored from its **PDF** uses its combined vector, against the papers' combined vectors;
- one scored from **title + abstract**, or **title** alone, uses that embedding, against the papers' abstract views;
- a **PDF** whose own abstract has 25 words or more is also compared by its title + abstract, as a title-and-abstract candidate would be (section 5).

An abstract of fewer than 25 words ([MIN_ABSTRACT_WORDS](RelevanceScorer.java#L153); words of two or more letters) counts as none: alerts put link text or a one-line snippet there, so that candidate is scored from its title alone.

These are the papers' vectors v₁ … vₙ in the formulas below.

- `centroid_s = normalize((v₁ + … + vₙ) / n)` ([line 1139](RelevanceScorer.java#L1139))
- `top3_s = mean of the 3 largest cos(c, vᵢ)`, or of all of them if n < 3 ([lines 1168–1169](RelevanceScorer.java#L1168))
- `sem_s = 0.5 · cos(c, centroid_s) + 0.5 · top3_s` ([line 1170](RelevanceScorer.java#L1170))
- `sem_s = 0` if the student has no papers ([line 1161](RelevanceScorer.java#L1161))

## 2. Wording similarity (TF-IDF)

Terms are stems and adjacent-stem pairs ([Terms](Terms.java)). N is the number of reference papers, and df(t) is how many of them contain term t.

- A term is kept only if `2 ≤ df(t) ≤ 0.5 · N` ([line 94](LexicalIndex.java#L94))
- `idf(t) = ln((N + 1) / (df(t) + 1)) + 1` ([line 95](LexicalIndex.java#L95))
- `w(t) = (1 + ln count(t)) · idf(t)`, then the vector is scaled to unit length ([lines 229–237](LexicalIndex.java#L229))
- `cos(a, b) = Σₜ a(t) · b(t)` ([line 251](LexicalIndex.java#L251))
- With x the candidate's TF-IDF vector and d₁ … dₙ the student's papers' vectors:
  `together_s = Σᵢ cos(x, dᵢ) / √(Σᵢ Σⱼ cos(dᵢ, dⱼ))`, which equals `cos(x, d₁ + … + dₙ)` ([lines 177–185](LexicalIndex.java#L177))
- `lex_s = 0.5 · together_s + 0.5 · mean of the 3 largest cos(x, dᵢ)` ([lines 191–193](LexicalIndex.java#L191))

## 3. Fit (z-scores across the students)

For each signal, with values x₁ … x_S (the sem or lex values of the S students) ([zScores](RelevanceScorer.java#L702)):

- `mean = (x₁ + … + x_S) / S` ([line 705](RelevanceScorer.java#L705))
- `sd = √( Σₛ (xₛ − mean)² / S )` ([lines 707–711](RelevanceScorer.java#L707))
- `zₛ = (xₛ − mean) / sd`; every z is 0 if sd < 1e-12 ([lines 713–717](RelevanceScorer.java#L713))
- `fit_s = (z_s(sem) + z_s(lex)) / 2` ([line 690](RelevanceScorer.java#L690))

## 4. Share (softmax)

- `share_s = exp(2 · (fit_s − max fit)) / Σₜ exp(2 · (fit_t − max fit))` ([lines 733–740](RelevanceScorer.java#L733))
- The shares sum to 1. Subtracting `max fit` ([lines 728–731](RelevanceScorer.java#L728)) doesn't change them; it only keeps `exp` from overflowing.

## 5. Relevance

**Anchors**, measured once per signal and per kind of evidence (PDF, title + abstract, title) ([calibrate](RelevanceScorer.java#L988)):
- Each distinct reference paper is held out and scored like a candidate with that evidence against every student; its own student is scored without it ([line 1008](RelevanceScorer.java#L1008)).
- `own` is the median similarity to its own student, and `other` the median similarity to the other students ([lines 1034–1043](RelevanceScorer.java#L1034), [median](RelevanceScorer.java#L1052)). For an even count the median is the upper of the two middle values.
- If they can't be measured, or `own ≤ other`, fixed fallback anchors are used ([anchors](RelevanceScorer.java#L1033)).

**Per candidate:**
- `n(sem) = (sem_s − other) / (own − other)`, and the same for lex ([line 248](RelevanceScorer.java#L248))
- `n = (n(sem) + n(lex)) / 2` ([position](RelevanceScorer.java#L785))
- For a PDF with an abstract of 25+ words: `n = 0.5 · n(PDF) + 0.5 · n(title + abstract)`, where `n(title + abstract)` is computed from the PDF's own title and abstract with the title-and-abstract anchors ([abstractPosition](RelevanceScorer.java#L803), [line 616](RelevanceScorer.java#L616)). The whole text of any seismic paper shares hundreds of words with every earthquake student's papers; the abstract says what the paper is about.
- With `-Dlitroute.semantic=abstract` (experimental), a PDF's meaning vector is already its title + abstract (on both sides), so this second blend is skipped: `n = (n(sem on title + abstract) + n(lex on the whole text)) / 2`.
- `relevance_s = 1 / (1 + e^(−ln 9 · (2n − 1)))` ([line 258](RelevanceScorer.java#L258)). This gives 0.1 at n = 0 (like another student's paper), 0.5 at n = 0.5 and 0.9 at n = 1 (like the student's own).

## 6. Score, assignment, recommendation

- `score_s = relevance_s · share_s` ([line 624](RelevanceScorer.java#L624))
- **Best student** = the one with the highest relevance, ties broken by score ([lines 627–630](RelevanceScorer.java#L627), [line 528](RelevanceScorer.java#L528)). Until November 2026 it was the one with the highest score.
- **Matched** to that student (queued, recommended) only if both hold, otherwise matched to no one ([lines 532–541](RelevanceScorer.java#L532)):
  - it is a research paper ([PaperCandidate.isResearch](../model/PaperCandidate.java); M2 already drops notes);
  - `relevance ≥ 0.85`, i.e. `n ≥ 0.89`: its similarity is at least 89% of the way from a typical other student's paper to a typical own paper; from a title alone, `relevance ≥ 0.6` (`n ≥ 0.59`). Until November 2026 the floors were 0.7 and 0.43; they were raised so that fewer papers reach a student they don't belong to, at the cost of missing some that do ([measurements.md](measurements.md), *Stricter floors*).
- From a title alone, `n = 0.65 · n(sem) + 0.35 · n(lex)` instead of the plain average.
- With an abstract or PDF, `relevance_title` is computed as for a title-only candidate, and if it is higher, `relevance += 0.6 · (relevance_title − relevance)`.
- No quota: a student gets every paper that qualifies.

With `SEMANTIC` or `LEXICAL` alone, steps 3 and 5 use just that one signal.

## Constants

| Constant | Value | Used in |
|---|---|---|
| [PROFILE_WEIGHT](RelevanceScorer.java#L85) | 0.5 | centroid / closest-papers blend (sections 1, 2) |
| [CLOSEST_PAPERS](RelevanceScorer.java#L86) | 3 | top-3 closest papers (sections 1, 2) |
| [SAME_PAPER](RelevanceScorer.java#L88) | 0.99 | cosine at which two papers count as one |
| [MIN_ABSTRACT_WORDS](RelevanceScorer.java#L153) | 25 | shorter abstracts count as none (section 1) |
| [MIN_PAPERS](LexicalIndex.java#L27) / [MAX_PAPER_SHARE](LexicalIndex.java#L28) | 2 / 0.5 | TF-IDF term filter (section 2) |
| [SHARE_SHARPNESS](RelevanceScorer.java#L94) | 2 | softmax (section 4) |
| [FULL_TEXT_WEIGHT](RelevanceScorer.java#L147) | 0.5 | a PDF's whole text against its title + abstract (section 5) |
| [LOG_9](RelevanceScorer.java#L185) | ln 9 | relevance curve (section 5) |
| [RELEVANCE_FLOOR](RelevanceScorer.java#L110) | 0.85 | match floor (section 6) |
| [TITLE_FLOOR](RelevanceScorer.java#L121) | 0.6 | match floor from a title alone (section 6) |
| [TITLE_MEANING_WEIGHT](RelevanceScorer.java#L128) | 0.65 | meaning weight from a title alone (section 6) |
| [TITLE_LIFT](RelevanceScorer.java#L136) | 0.6 | title lift (section 6) |
