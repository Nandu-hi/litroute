Good — that maps to clustering per-profile embeddings into a few "interest centroids" instead of one blurred average or an unbounded per-PDF comparison list. Here's the concrete M5 plan, following the existing package/store conventions in the codebase.

## New dependencies (`pom.xml`)

- `ai.djl.huggingface:tokenizers` + `ai.djl.onnxruntime:onnxruntime-engine` (Deep Java Library) — gives you a real HuggingFace-compatible tokenizer plus an in-JVM ONNX Runtime, so SPECTER2 runs locally with no Python at inference time and tests stay offline, matching invariant #7 in `temp.txt`.
- `org.apache.pdfbox:pdfbox` — the codebase has nothing that reads PDF *content* today (`PdfDownloader` only saves bytes), so extracting title/abstract/body text from a profile PDF is new.

## One-time, offline model prep (not part of the Java build)

SPECTER2 ships as a SciBERT base + a HuggingFace *adapter* (proximity mode, for "papers like this paper"), which ONNX can't run directly — the adapter has to be merged into the base weights first, then exported. This is a dev-time step (Python, `adapters` + `optimum-cli export onnx`), done once, producing `specter2.onnx` + `tokenizer.json`. Given the model is too large to commit to git, M5 downloads it on first use via the existing `HttpFetcher`/`AtomicFiles` pattern into `~/.litroute/models/specter2.onnx`, exactly like `PdfDownloader` caches PDFs — after that it's local-only.

## New package `ingestion/relevance/` (M5)

- `EmbeddingProvider.java` — interface `float[] embed(String text)`, the seam that keeps tests offline (a `FakeEmbeddingProvider` stands in for it in tests, same role `FakeHttp` plays for M3).
- `Specter2EmbeddingProvider.java` — real implementation: DJL tokenizer + ONNX session, builds SPECTER2's `title [SEP] abstract` input convention, mean-pools/[CLS]-pools the output, L2-normalizes.
- `PdfTextExtractor.java` — PDFBox-based; pulls title, abstract, and enough additional body text ("pdf information") to enrich the embedding input, truncated to SPECTER2's ~512-token limit.
- `EmbeddingClusterer.java` — greedy/agglomerative clustering over a profile's per-PDF embeddings: merge any two whose cosine similarity exceeds a threshold (e.g. 0.85) into the same cluster, output one centroid (mean, re-normalized) per cluster. This is what turns "20 PDFs" into "3–5 interest centroids" instead of one blurred average or 20 pairwise comparisons.
- `RelevanceScorer.java` — public entry point: `score(PaperCandidate, ProfileEmbedding)` → embeds the candidate's title+abstract (cached), takes the **max** cosine similarity against the profile's cluster centroids. Mirrors `MetadataSource`/`Deduplicator`'s pattern of a named `double` threshold constant owned by this class, not reused from M3/M4.
- `util/Vectors.java` — `cosineSimilarity(float[], float[])`, `meanPool`, `normalize`. Small, dependency-free, parallel to `Titles.java`.

## Model layer

- `model/ScoredCandidate.java` — `record ScoredCandidate(PaperCandidate candidate, double relevanceScore)`, the M5 output, same style as `PaperCandidate`/`ParsedPaper`.

## New M7 stores (fills the `M7d`/`M7e` slots `temp.txt` flags as not started)

- `store/ProfileEmbeddingCacheStore.java` (**M7d**) — persists each profile PDF's embedding (keyed by content hash, so editing/adding a PDF invalidates just that entry) plus the derived cluster centroids; recomputes clusters only when the PDF set's combined hash changes. JSON via Jackson + `AtomicFiles`, same shape as `EnrichmentCacheStore`.
- `store/CandidateEmbeddingCacheStore.java` (**M7e**) — candidate embeddings keyed by `Titles.compact(title)`, same key strategy `CachingPaperEnricher` already uses for lookups, so re-scoring a batch never re-embeds a paper it's seen before.

## Where M5 sits in the pipeline

`AlertBatchProcessor` (or M9 once it exists) runs M1→M4 exactly as today, then hands the deduplicated `List<PaperCandidate>` to `RelevanceScorer` against the cached profile embedding, producing `List<ScoredCandidate>` for M6 to filter/mail. No change to the M1–M4 write order or the "email always wins" invariant — M5 only reads `PaperCandidate`, never mutates it.

## Testing

Unit tests for `EmbeddingClusterer` and `RelevanceScorer` use hand-crafted `float[]` vectors via a fake `EmbeddingProvider`, so they stay fast/offline like the other 131 tests. A separate, explicitly-marked integration test exercises the real ONNX model against a couple of sample PDFs/emails to catch tokenizer or export drift — this one needs the model file present, so it's opt-in rather than part of the default `mvn test` run.

---

Want me to start implementing, and if so, in what order — e.g., `Vectors`/`EmbeddingClusterer` first since they're pure and testable without the model, then the PDFBox extractor, then the ONNX provider last?