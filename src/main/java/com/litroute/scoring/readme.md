# The `scoring` module (M6)

Matches each deduplicated paper to the **one** student it suits best, and recommends each student's best 3 papers of the batch. A paper is compared with every student's reference papers by meaning (SPECTER2) and by wording (TF-IDF). Design decisions and measurements: [m6plan.txt](../../../../../../m6plan.txt).

A second matcher asks a language model instead (on Groq): see [AI matching](#ai-matching). Both are a [PaperMatcher](PaperMatcher.java) and give the same output.

## Input and output

- **In:**
  - every student's [Profile](../model/Profile.java) from M7d: their papers' vectors and text, built with the same model (other models are rejected);
  - the batch: deduplicated [PaperCandidate](../model/PaperCandidate.java)s from M7c, or local PDFs via [LocalPdfCandidates](LocalPdfCandidates.java);
  - SPECTER2 ([EmbeddingProvider](../embedding/EmbeddingProvider.java)).
- **Out:** `Map<profile name, List<ScoredPaper>>` ([PaperMatcher.match](PaperMatcher.java)): every student, in profile order, with the papers assigned to them, highest score first (an empty list if none). Each [ScoredPaper](../model/ScoredPaper.java) has a score from 0 to 1, a rationale, `PENDING` status and a `recommended` flag. Every paper is in exactly one list; one that isn't recommended says why. The CLI queues them in M7e, which copies each matched PDF into the student's folder.

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

1. **Evidence** ([read](RelevanceScorer.java#L515)): its PDF if readable, else title + abstract, else title alone. A PDF is compared with the papers' combined vectors, the rest with their abstract views.
2. **Meaning** ([Space.closeness](RelevanceScorer.java#L680)), for each student:
   ```
   closeness = ½ · cos(c, centroid) + ½ · mean cos(c, the student's 3 closest papers)
   centroid  = normalize(mean of the student's paper vectors)
   ```
   - Copies of a paper (same hash, or cosine ≥ 0.99) count once ([sameAs](RelevanceScorer.java#L622)).
   - The centroid is computed on [line 672](RelevanceScorer.java#L672) and the blend on [line 691](RelevanceScorer.java#L691).
   - Paper vectors are unit length, so cos(c, centroid) is the average similarity to all the student's papers divided by |mean|: fit with their work as a whole. The closest-papers half rewards matching one part of it.
3. **Wording** ([LexicalIndex.closeness](LexicalIndex.java#L115)): the same ½ + ½ blend over TF-IDF vectors.
4. **Fit** ([fit](RelevanceScorer.java#L417)): each signal is standardized across the students, then the two are averaged, so a generic paper stands out for no one. For one signal, with similarities x₁ … x_S for the S students ([zScores](RelevanceScorer.java#L429)):
   - `mean = (x₁ + … + x_S) / S` ([line 432](RelevanceScorer.java#L432))
   - `sd = √( ((x₁ − mean)² + … + (x_S − mean)²) / S )`, dividing by S, not S − 1 ([lines 436–438](RelevanceScorer.java#L436))
   - `z_s = (x_s − mean) / sd`; if sd is (near) 0, below 1e-12, every z is 0, since the signal can't tell the students apart ([lines 440–442](RelevanceScorer.java#L440))
   - `fit_s = (z_s of meaning + z_s of wording) / 2`; `SEMANTIC` or `LEXICAL` alone uses that one signal's z ([line 423](RelevanceScorer.java#L423))
5. **Share** ([softmax](RelevanceScorer.java#L448)): how much of the paper belongs to each student, with sharpness k = 2 ([SHARE_SHARPNESS](RelevanceScorer.java#L88)):
   - `share_s = exp(k · (fit_s − max fit)) / Σₜ exp(k · (fit_t − max fit))` ([lines 456–460](RelevanceScorer.java#L456))
   - Subtracting the largest fit ([line 451](RelevanceScorer.java#L451)) leaves the shares unchanged and keeps `exp` from overflowing. The shares sum to 1; a larger k gives the best student more of the share.
6. **Relevance** ([relevance](RelevanceScorer.java#L465), [Anchors](RelevanceScorer.java#L164)): each similarity is placed between an `other` and an `own` anchor and passed through a logistic curve, so it is about 0.9 for a typical own paper and 0.1 for another student's. The anchors are medians measured by holding out each reference paper in turn, per kind of evidence ([calibrate](RelevanceScorer.java#L564)).
7. **Score** = relevance × share ([line 371](RelevanceScorer.java#L371)). The rationale gives the share and runner-up, the relevance, the shared words, the closest papers and the evidence used.

## Assigning and recommending

[match](RelevanceScorer.java#L287) gives each paper to its highest-scoring student, but only if it is a research paper (not a book review, discussion, erratum and the like; [PaperCandidate.isResearch](../model/PaperCandidate.java), which M2 already applies) with relevance ≥ 0.7 ([RELEVANCE_FLOOR](RelevanceScorer.java#L98)). Any other paper matches no one and is not queued. There is no quota per student: a student gets every paper that qualifies, however many or few.

**Why 0.7 and no quota** (October 2026, the professor's first reviewed batch of 31 alert papers): with the old rule every paper was queued and each student's top 3 above 0.5 recommended, so 20 of the 31 queued papers were dropped, 15 of them landing on RP4-mh-char, the broadest hazard profile. Five were book reviews, a discussion, a closure and an erratum (one book review scored relevance 0.96); nine were below 0.5. Above 0.5, six of the 17 research papers were generic hazard or disaster-management papers he dropped, and the quota of 3 had cut a rainstorm hazard-chain paper (relevance 0.91) from RP4. Raising the floor while queuing only what clears it:

| Rule | Queued | Approved by the professor | Dropped by him |
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

On the batch: 16 of 26 matched, the 14 the professor wants plus the flood cost–benefit and drought–flood papers (one out of scope, one borderline). On his filed papers known by title and abstract, no match changes; judged by title alone, 34 of 46 reach their student (32 before) and 5 reach another (3 before).

**Result** on the 48 papers the professor filed by student, from full PDFs: `HYBRID` (the default) assigns 46 correctly and gets every top 3 right. `SEMANTIC` alone gets 43 and P@3 0.81; `LEXICAL` alone gets 43 and 0.88.

## TF-IDF

- [Terms](Terms.java): lowercase, accent-folded stems plus pairs of adjacent stems (`rank_aggregation`). Words under 3 letters, function words and boilerplate are dropped, and a dropped word breaks a pair.
- [LexicalIndex](LexicalIndex.java#L52): `idf = ln((N+1)/(df+1)) + 1` ([line 68](LexicalIndex.java#L68)); each term is weighted `(1 + ln(count)) × idf` ([weigh](LexicalIndex.java#L162)); terms in only one paper or in more than half of them are dropped; vectors are unit length.

## AI matching

[AiPaperMatcher](ai/AiPaperMatcher.java) sends every student and the whole batch to a language model in one prompt and gets back, per student, the relevant papers with a 0–100 score and the one paper to focus on, with a one-line reason. Each student is described by the keywords M5 stored with their profile ([ProfileKeywords](../profile/ProfileKeywords.java)) and their six most typical paper titles. The CLI doesn't run it yet.

```
ProfileBrief per student            the batch (M7c)
 (name, keywords, 6 typical titles)  │
        └──────────┬─────────────────┘
                   ▼
   split the batch into as few requests as keep each prompt within the token window
   (8,000 − 2,500 for the answer = 5,500 tokens on the free tier); every request names every student
                   │
                   ▼
   MatchPrompt: text (students by name, papers as P1..Pn, abstracts cut to 300 characters)
              + JSON Schema (only those names and ids allowed)
                   │
                   ▼
   LlmClient.generateJson ── GroqClient ── POST api.groq.com/openai/v1/chat/completions
                                           openai/gpt-oss-120b, strict Structured Outputs
                   │
                   ▼
   check the answer: unknown students / paper ids dropped, scores clamped to 0–100,
                     a focus paper outside the student's matches gives way to their best match
                   │
                   ▼
   Map<student, List<ScoredPaper>>   score = AI score / 100, recommended = the focus paper,
                                     rationale = the model's one-line reason
```

- **One paper, several students:** unlike `RelevanceScorer`, the model may match a paper to every student it is relevant to.
- **[LlmClient](ai/LlmClient.java)** is the seam for the provider. [GroqClient](ai/GroqClient.java) implements it, posting through [JsonPoster](ai/JsonPoster.java) / [JdkJsonPoster](ai/JdkJsonPoster.java) and [ApiCalls](ai/ApiCalls.java).
- **Settings:** `GROQ_API_KEY` (required, from [console.groq.com](https://console.groq.com/keys)); it is sent only in the `Authorization: Bearer` header and never saved. `GROQ_MODEL` overrides the default `openai/gpt-oss-120b`, and must be a model Groq offers strict Structured Outputs for (`openai/gpt-oss-20b` also works).
- **JSON output:** `response_format` = `json_schema` with `strict: true`. Strict mode wants every object closed and all its fields required, so [GroqClient.strictSchema](ai/GroqClient.java) rewrites the matcher's schema: optional fields become nullable, numeric bounds are dropped (the matcher clamps scores itself). The model reasons briefly (`reasoning_effort: low`) and its reasoning isn't sent back (`include_reasoning: false`).
- **Fitting the free tier:** Groq's free limit for these models is 8,000 tokens a minute (and 200,000 a day), counting the prompt and the answer. So a student is described by the titles of their 6 most typical papers, those whose SPECTER2 vectors are closest to the student's mean ([ProfileBrief.from](ai/ProfileBrief.java)), abstracts are cut to 300 characters, and the answer may use up to 2,500 tokens. For the 8 students and 31 sample alert papers the prompt is 17,700 characters (≈ 4,400 tokens); with all ~300 titles and full abstracts it was 61,000.
- **Always within the token window:** [AiPaperMatcher](ai/AiPaperMatcher.java) never sends more papers than fit. A request may hold what the answer leaves of the tokens a minute ([LlmClient.maxPromptTokens](ai/LlmClient.java); for Groq 8,000 − 2,500 = 5,500, or `GROQ_TOKENS_PER_MINUTE` − 2,500 on a paid account). Prompts are measured at 4 characters a token, which over-estimates (Groq counted 15,916 characters as 3,512 tokens). A batch that doesn't fit is split into as few requests as do, runs of near-equal length in batch order, each naming every student. The next request waits as long as Groq's rate-limit reply asks. Each request picks a focus paper per student, and the student keeps the best-scored one; the others keep the model's reason as their rationale but aren't recommended.
- **Errors:** a 429 or 5xx is retried after 10 s, 30 s and 60 s, or after the wait Groq names ("Please try again in 7.66s") if longer. A used-up daily limit names hours and is reported at once. A refused request (400, or 413 too large), an answer cut short (`finish_reason: length`), or one that isn't JSON is an `IOException`, never an empty result.

**Recording the test reply.** The tests replay one real Groq answer. Record it again whenever the prompt or the profiles change:

```
GROQ_API_KEY=... mvn -q test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.litroute.scoring.ai.AiRecorder
```

[AiRecorder](../../../../../test/java/com/litroute/scoring/ai/AiRecorder.java) sends the stored profiles and the client's sample alert papers, saves `src/test/resources/ai/groq-match-request.json` (without the key) and `groq-match-reply.json` (only for a successful reply), and prints the matches. `-Dexec.args=--dry-run` prints the prompt instead and sends nothing.

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

Real data only: the real model, the real profiles, the professor's filed papers, the client's real alert emails, and a real Groq reply.

- [RelevanceScorerTest](../../../../../test/java/com/litroute/scoring/RelevanceScorerTest.java):
  - a paper goes to at most one student, its best, and only with relevance ≥ 0.7; every student is listed, in profile order, papers best first;
  - filed papers reach their student: ≥ 85% of the readable ones, from the PDF and from title + abstract alone, with ≥ 90% of matches right;
  - P@3 ≥ 0.8;
  - hybrid beats SPECTER2 alone;
  - every score is in 0–1 and explained;
  - blank, other-model and text-less profiles are handled;
  - from the client's alerts, the relevant SSI review is matched; the irrelevant ceramic-coating paper is not (M2 has already dropped the discussion, closure, book reviews and erratum).
- [AiPaperMatcherTest](../../../../../test/java/com/litroute/scoring/ai/AiPaperMatcherTest.java), on Groq's recorded answer: every student listed in order, papers best first and only from the batch; one focus paper per matched student with a one-line reason; the prompt names every student and paper; invented students and paper ids are dropped; a stray focus gives way to the best match; an empty batch asks nothing; each student is described by at most 6 titles and their keywords; the sample batch fits the token window in one request, and a batch too long for a smaller window goes in as few requests as fit, each within it, naming every paper once and in order, with still one focus paper per student.
- [GroqClientTest](../../../../../test/java/com/litroute/scoring/ai/GroqClientTest.java): the request (endpoint, key only in a header, strict JSON schema, low reasoning, answer budget); the strict-mode rewrite; the recorded answer parses; a per-minute rate limit is waited out and a used-up daily one reported at once; the wait is read from Groq's message; a lasting outage, a refused or too-large request and a truncated answer are errors; key and model from the environment; the prompt budget is the tokens a minute (free tier, or `GROQ_TOKENS_PER_MINUTE`) less the answer.
- [LexicalIndexTest](../../../../../test/java/com/litroute/scoring/LexicalIndexTest.java), [TermsTest](../../../../../test/java/com/litroute/scoring/TermsTest.java): held-out papers are closest to their own student; term folding, stems and pairs.
