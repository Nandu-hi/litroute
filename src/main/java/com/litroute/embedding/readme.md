# `embedding`: SPECTER2, run locally

Turns `(title, text)` into a 768-d unit vector with SPECTER2 (`specter2_base` + proximity adapter) on ONNX Runtime. No Python or network is needed at runtime. M5 and M6 both use it, so profiles and candidates share one vector space.

```
"title[SEP]text" ─tokenize, ≤ 512 tokens─▶ ONNX ─▶ [CLS] ─normalize─▶ float[768]
```

| Class | Role |
|---|---|
| [EmbeddingProvider](EmbeddingProvider.java) | `embed(title, text)`, `modelId()`, `close()`; swap models here |
| [Specter2EmbeddingProvider](Specter2EmbeddingProvider.java) | Loads `~/.litroute/models`. Missing files → `IOException` naming `setup_model.sh`. Truncates long text. Thread-safe. |

`modelId()` is saved with every profile. ProfileBuilder re-embeds papers saved under another id, and RelevanceScorer refuses them, so vector spaces never mix.

**Tests:** [Specter2EmbeddingProviderTest](../../../../../test/java/com/litroute/embedding/Specter2EmbeddingProviderTest.java), on the real model: unit length, deterministic, related papers closer, truncation, empty text, missing files.
