# The `embedding` module

**In one sentence:** it turns a paper's title and text into a 768-number SPECTER2 vector (always unit length), computed locally with no Python and no network, so M5 (profiles) and M6 (scoring) compare papers by meaning using the same model.

## Input and output

| | Type | What it holds |
|---|---|---|
| **Input** | Model files in `~/.litroute/models` (from [defaultModelDirectory()](Specter2EmbeddingProvider.java#L46)) | `specter2.onnx` (+ `specter2.onnx.data`) and `tokenizer.json`. These are produced once per machine by `scripts/model-export/setup_model.sh`. |
| **Input** | `embed(title, text)` | A paper's title plus one piece of text describing it: its abstract, its section headings, or a passage of its body. The text may be empty. |
| **Output** | `float[]` | A unit-length vector (768 dimensions for SPECTER2). |
| **Output** | `modelId()` | A string that identifies the model and how its input is shaped (`specter2-proximity-onnx/cls/512`). It is saved with every vector so vectors from different models are never mixed. |

## How it works

```
title, text
   │
   ▼  "title[SEP]text"                 SPECTER2's input convention
   │
   ▼  HuggingFaceTokenizer (DJL)       → token ids + attention mask, cut to 512 tokens
   │
   ▼  OrtSession.run (ONNX Runtime)    → [CLS] vector of the proximity model
   │
   ▼  Vectors.normalize                → unit length
   │
float[]  ──▶  M5 PaperEmbedder (profile papers)
         ──▶  M6 RelevanceScorer (candidate papers)
```

Both sides of every comparison go through the same provider, so a student's papers and an incoming paper are always in the same vector space.

### [EmbeddingProvider](EmbeddingProvider.java): the interface

This is the **Strategy** seam from the design doc. The rest of the code depends only on this interface, so the local model could be replaced by a public embedding API without changing anything else. It has three methods:

- `modelId()`: identifies the model. [ProfileBuilder](../profile/ProfileBuilder.java) re-embeds papers saved with a different id, and [RelevanceScorer](../scoring/RelevanceScorer.java) refuses to compare against profiles built by another model.
- `embed(title, text)`: returns a unit-length vector. Text that is too long is truncated, never rejected.
- `close()`: releases the model. The interface extends `AutoCloseable`, so callers use try-with-resources.

### [Specter2EmbeddingProvider](Specter2EmbeddingProvider.java): the implementation

This runs **SPECTER2** (`allenai/specter2_base` with the *proximity* adapter, the mode meant for paper-to-paper similarity) locally.

- **[open(modelDirectory)](Specter2EmbeddingProvider.java#L55)** checks that both model files exist and throws an `IOException` naming the setup script if they don't. It then loads the tokenizer with truncation at 512 tokens (the tokenizer config declares no usable maximum, so the limit is set here) and opens an ONNX Runtime session. The session is loaded by **file path**, not by bytes, so ONNX Runtime can find the separate weights file `specter2.onnx.data`. [atDefaultLocation()](Specter2EmbeddingProvider.java#L50) opens `~/.litroute/models`.
- **[embed(title, text)](Specter2EmbeddingProvider.java#L84)** tokenizes `title + "[SEP]" + text`, builds two `1 × n` tensors (`input_ids`, `attention_mask`), runs the model, takes the returned [CLS] vector and normalizes it with [Vectors.normalize](../util/Vectors.java#L45). Inference errors become an `IllegalStateException`.
- **Thread-safe:** ONNX Runtime sessions and DJL tokenizers can be shared between threads, which is why M6 can embed candidates in parallel.

## How it's tested

The test uses the **real exported model**, as `scripts/model-export/verify_onnx.py` does in Python.

| Test class | What it covers |
|---|---|
| [Specter2EmbeddingProviderTest](../../../../../test/java/com/litroute/embedding/Specter2EmbeddingProviderTest.java) | An embedding is a 768-dimensional unit vector. The same text gives the same vector. Related papers are closer than unrelated ones. Text over the 512-token limit is truncated, not rejected. Empty text still embeds the title. Missing model files produce an error naming the setup script. |
