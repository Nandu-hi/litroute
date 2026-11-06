# `embedding`: SPECTER2, run locally

Turns `(title, text)` into a 768-dimension unit vector with SPECTER2 (`allenai/specter2_base` plus the *proximity* adapter), using ONNX Runtime and the DJL tokenizer. No Python and no network are needed at runtime. M5 and M6 both go through it, so profiles and candidates share one vector space.

```
"title[SEP]text" ─tokenize, cut to 512 tokens─▶ ONNX session ─▶ [CLS] vector ─normalize─▶ float[768]
```

| Class | Role |
|---|---|
| [EmbeddingProvider](EmbeddingProvider.java) | The interface: `embed(title, text)`, `modelId()`, `close()`. Swapping the model means implementing this interface and nothing else. |
| [Specter2EmbeddingProvider](Specter2EmbeddingProvider.java) | `atDefaultLocation()` opens `~/.litroute/models` (`specter2.onnx`, `specter2.onnx.data`, `tokenizer.json`). Missing files → an `IOException` that names `setup_model.sh`. Over-long text is truncated, never rejected. Thread-safe, so M6 embeds in parallel. |

**`modelId()`** (`specter2-proximity-onnx/cls/512`) is saved with every profile. ProfileBuilder re-embeds papers saved under another id, and RelevanceScorer refuses such profiles, so vector spaces are never mixed. Candidate vectors are never stored.

**Tests:** [Specter2EmbeddingProviderTest](../../../../../test/java/com/litroute/embedding/Specter2EmbeddingProviderTest.java) runs the real model. It checks for a 768-d unit vector, a deterministic output, related papers closer than unrelated ones, truncation past 512 tokens, empty text, and the error for missing model files.
