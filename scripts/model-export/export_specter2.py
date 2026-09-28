"""
One-time export: allenai/specter2_base + the SPECTER2 'proximity' adapter
(paper-to-paper similarity mode) merged and exported to ONNX, plus the
matching fast tokenizer, so LitRoute's M5 relevance scorer can run
inference in the JVM (onnxruntime + DJL tokenizers) with no Python at
runtime.

Usage:
    ./setup_model.sh                 (recommended - handles the venv)
or directly, inside an environment with the packages from
requirements.txt installed:
    python export_specter2.py

Writes specter2.onnx, specter2.onnx.data, tokenizer.json,
tokenizer_config.json, vocab.txt and special_tokens_map.json to
$SPECTER2_OUT_DIR (default: ~/.litroute/models).
"""
import json
import os

import torch
from adapters import AutoAdapterModel
from transformers import AutoTokenizer

BASE_MODEL = "allenai/specter2_base"
ADAPTER = "allenai/specter2"  # the proximity (paper-similarity) adapter
OUT_DIR = os.environ.get("SPECTER2_OUT_DIR", os.path.expanduser("~/.litroute/models"))

os.makedirs(OUT_DIR, exist_ok=True)

print(f"Loading base model {BASE_MODEL} ...")
model = AutoAdapterModel.from_pretrained(BASE_MODEL)

print(f"Loading + activating adapter {ADAPTER} (proximity mode) ...")
adapter_name = model.load_adapter(ADAPTER, source="hf", set_active=True)
print(f"Active adapter: {adapter_name}")
model.eval()


class Specter2Embedder(torch.nn.Module):
    """Wraps the adapter-augmented model so ONNX export sees a plain
    forward(input_ids, attention_mask) -> pooled_embedding graph.
    SPECTER2's convention: the paper embedding is the [CLS] token of the
    last hidden state (index 0), NOT mean pooling."""

    def __init__(self, inner):
        super().__init__()
        self.inner = inner

    def forward(self, input_ids, attention_mask):
        out = self.inner(input_ids=input_ids, attention_mask=attention_mask)
        return out.last_hidden_state[:, 0, :]


wrapped = Specter2Embedder(model)
wrapped.eval()

print(f"Loading tokenizer {BASE_MODEL} ...")
tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL, use_fast=True)

sample_text = (
    "Attention is all you need"
    + tokenizer.sep_token
    + "The dominant sequence transduction models are based on complex "
    "recurrent or convolutional neural networks."
)
inputs = tokenizer(sample_text, return_tensors="pt", padding="max_length", truncation=True, max_length=512)

print("Running a sanity forward pass in PyTorch ...")
with torch.no_grad():
    reference_embedding = wrapped(inputs["input_ids"], inputs["attention_mask"])
print("PyTorch output shape:", reference_embedding.shape)

onnx_path = os.path.join(OUT_DIR, "specter2.onnx")
print(f"Exporting to {onnx_path} ...")
# NOTE: the output name must not collide with any internal node name in the
# graph (e.g. "embedding") - torch's exporter will silently produce an
# invalid model ("Duplicate definition of name") if it does.
torch.onnx.export(
    wrapped,
    (inputs["input_ids"], inputs["attention_mask"]),
    onnx_path,
    input_names=["input_ids", "attention_mask"],
    output_names=["paper_embedding"],
    dynamic_axes={
        "input_ids": {0: "batch", 1: "sequence"},
        "attention_mask": {0: "batch", 1: "sequence"},
        "paper_embedding": {0: "batch"},
    },
    opset_version=17,
)

print("Saving tokenizer files ...")
tokenizer.save_pretrained(OUT_DIR)

print("Done. Contents of", OUT_DIR, ":")
for f in sorted(os.listdir(OUT_DIR)):
    path = os.path.join(OUT_DIR, f)
    if os.path.isfile(path):
        print(f"  {f}  ({os.path.getsize(path)} bytes)")

# Stashed for verify_onnx.py to compare the exported graph against.
with open(os.path.join(OUT_DIR, "_reference_embedding.json"), "w") as f:
    json.dump(reference_embedding.squeeze(0).tolist(), f)
