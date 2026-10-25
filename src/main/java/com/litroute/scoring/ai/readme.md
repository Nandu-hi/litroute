# The `scoring.ai` module (M6, AI matcher)

**In one sentence:** the second M6 matcher: it describes every student and the batch's papers to a language model on Groq, asks for a strict JSON answer (per student, the relevant papers with a 0–100 score and one paper to read first), checks that answer, and returns the same `Map<student, List<ScoredPaper>>` as [RelevanceScorer](../RelevanceScorer.java).

It is chosen per run (**Matcher: Groq language model** in the UI, or `PipelineRunnerCli --matcher=ai`) through [PipelineRunner.matcher](../../pipeline/PipelineRunner.java). The measurements and design choices are in the [scoring readme](../readme.md#ai-matching).

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** | `Collection<Profile>` (M7d) | Each student becomes a [ProfileBrief](ProfileBrief.java): name, the keywords M5 kept, and the titles of the 6 papers closest to the student's mean vector. |
| **Input** | `List<PaperCandidate>` (M7c) | The batch: title, venue, year, type and the abstract (cut to 300 characters). |
| **Input** | `GROQ_API_KEY`, optionally `GROQ_MODEL`, `GROQ_TOKENS_PER_MINUTE` | Read from the environment by [GroqClient.fromEnvironment](GroqClient.java). The key is only ever sent in the `Authorization` header. |
| **Output** | `Map<String, List<ScoredPaper>>` | Every student, in profile order; their matched papers best first, score = AI score / 100, rationale = the model's reason (or "scored this N/100"), relevance empty, one paper recommended (the focus pick). A paper may go to several students. |

## How data flows through the module

```
Profiles (M7d) ──ProfileBrief.from──▶ briefs            batch (M7c, papers not yet scored)
                                         │                   │
                                         ▼                   ▼
                        AiPaperMatcher.match(batch)
                           requests(batch): as few near-equal runs as keep
                           MatchPrompt.estimateTokens(MatchPrompt.text(...)) ≤ LlmClient.maxPromptTokens()
                                         │ for each run
                                         ▼
                        ask(run): MatchPrompt.text (prompt) + MatchPrompt.schema (enum of names / P1..Pn)
                                         │
                                         ▼
                        LlmClient.generateJson ── GroqClient
                              requestBody + strictSchema ──▶ ApiCalls.post (retry 429/5xx: 10/30/60 s,
                                                                or the wait Groq names; give up if hours)
                                                              └─▶ JsonPoster ── JdkJsonPoster (HTTPS POST)
                              answer(): first choice's content, finish_reason must be "stop"
                                         │
                                         ▼
                        checked: unknown profile / paper id dropped (logged), scores clamped 0..100,
                                 a paper listed twice keeps its higher score
                                         │
                                         ▼
                        scored(student): best first; recommended = best-scored focus pick (else best match)
                                         │
                                         ▼
                        Map<student, List<ScoredPaper>> ──▶ M9 ──▶ M7e
```

## The classes

| Class | Role |
|---|---|
| [AiPaperMatcher](AiPaperMatcher.java) | The `PaperMatcher`: splits the batch to fit the token window, asks the model once per run of papers, checks and merges the answers, builds the ScoredPapers. |
| [ProfileBrief](ProfileBrief.java) | What the model is told about one student: name, keywords, 6 most typical titles. |
| [MatchPrompt](MatchPrompt.java) | Package-private. The prompt text, the answer's JSON Schema, paper ids (P1..Pn) and the token estimate (4 characters a token). |
| [LlmClient](LlmClient.java) | The provider seam: `modelId()`, `maxPromptTokens()`, `generateJson(prompt, schema)`. |
| [GroqClient](GroqClient.java) | LlmClient on Groq's chat-completions API with strict Structured Outputs; reads key, model and tokens a minute from the environment; rewrites the schema for strict mode. |
| [ApiCalls](ApiCalls.java) | Package-private. POST with retries for throttled or overloaded replies, reading "try again in 7.66s" waits; error messages from `error.message`. |
| [JsonPoster](JsonPoster.java) | The network seam (tests replay a recorded Groq reply through it). |
| [JdkJsonPoster](JdkJsonPoster.java) | JsonPoster on the JDK HttpClient: HTTPS only, 8 s connect, 3 min request timeout, reply size cap. |

## Interaction with M7 (the stores)

Like the rest of M6, this module reads its inputs from stores through M9 and writes none itself.

- **M7d → here:** only `name`, `keywords` and the papers' `embedding` (to pick the 6 typical titles) and `title` are used. A profile without keywords is still briefed, by its titles.
- **M7c → here:** M9 passes the deduplicated papers not yet in `scored_papers.json` or the queue. Because the model may give a paper to **no one**, `scored_papers.json` (not M7e) is what stops the same paper being sent to the model on every run.
- **here → M7e:** M9 drops matches already in `sent_papers.json`, then `ScoredQueueStore.addAll` queues the rest. A paper matched to two students is queued twice and its PDF copied into both students' `matched/` folders. `relevance` is stored as null, which the Review tab shows as "–".
- If Groq fails (`IOException`), M9 stops before writing M7e or `scored_papers.json`, so the papers are offered again on the next run; M7a and M7c already hold the batch, so nothing is fetched twice.

## How it's tested

| Test class | What it covers |
|---|---|
| [AiPaperMatcherTest](../../../../../../test/java/com/litroute/scoring/ai/AiPaperMatcherTest.java) | On Groq's recorded answer: every student listed in order, papers best first and only from the batch; one focus paper per matched student; unknown students and ids dropped; a stray focus gives way to the best match; an empty batch asks nothing; briefs hold at most 6 titles; batches are split to fit the token window. |
| [GroqClientTest](../../../../../../test/java/com/litroute/scoring/ai/GroqClientTest.java) | The request body and strict-schema rewrite, parsing the recorded answer, retries and asked waits, errors for refused, too large or truncated answers, settings from the environment. |
| [AiRecorder](../../../../../../test/java/com/litroute/scoring/ai/AiRecorder.java) | Not a test: records a fresh real Groq reply for the tests (see the scoring readme). |
