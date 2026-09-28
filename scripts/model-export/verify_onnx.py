"""
Sanity check for export_specter2.py's output: runs the exported ONNX model
with onnxruntime (the same engine the Java side uses) and confirms it
agrees with the PyTorch reference embedding stashed by the export step,
then checks the embeddings are semantically sane (two related papers score
more similar than an unrelated one).

Usage: python verify_onnx.py   (after export_specter2.py has run)
"""
import json
import os

import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

OUT_DIR = os.environ.get("SPECTER2_OUT_DIR", os.path.expanduser("~/.litroute/models"))

tokenizer = AutoTokenizer.from_pretrained(OUT_DIR)
session = ort.InferenceSession(os.path.join(OUT_DIR, "specter2.onnx"), providers=["CPUExecutionProvider"])


def embed(title, abstract):
    text = f"{title}{tokenizer.sep_token}{abstract}"
    inputs = tokenizer(text, return_tensors="np", padding="max_length", truncation=True, max_length=512)
    out = session.run(
        ["paper_embedding"],
        {
            "input_ids": inputs["input_ids"].astype(np.int64),
            "attention_mask": inputs["attention_mask"].astype(np.int64),
        },
    )[0][0]
    return out / np.linalg.norm(out)


# 1. Exported graph must agree with the PyTorch reference computed at export time.
reference_path = os.path.join(OUT_DIR, "_reference_embedding.json")
if os.path.exists(reference_path):
    onnx_out = embed(
        "Attention is all you need",
        "The dominant sequence transduction models are based on complex recurrent "
        "or convolutional neural networks.",
    )
    with open(reference_path) as f:
        reference = np.array(json.load(f))
    reference = reference / np.linalg.norm(reference)
    cos_sim = float(np.dot(onnx_out, reference))
    print(f"Cosine similarity ONNX vs PyTorch reference: {cos_sim:.6f}")
    assert cos_sim > 0.999, "ONNX export diverges from the PyTorch model - do not use this .onnx file"
else:
    print(f"(skipping PyTorch-reference check: {reference_path} not found)")

# 2. Embeddings must be semantically meaningful, not just internally consistent.
a = embed(
    "Attention Is All You Need",
    "We propose a new network architecture, the Transformer, based solely on "
    "attention mechanisms, dispensing with recurrence and convolutions entirely.",
)
b = embed(
    "BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding",
    "We introduce a new language representation model called BERT, using a masked "
    "language model pre-training objective.",
)
c = embed(
    "CRISPR-Cas9 gene editing in mammalian cells",
    "We demonstrate targeted genome editing in mammalian cells using the CRISPR-Cas9 "
    "system, achieving efficient double-strand breaks at specified loci.",
)

sim_ab, sim_ac, sim_bc = float(np.dot(a, b)), float(np.dot(a, c)), float(np.dot(b, c))
print(f"Transformer paper <-> BERT paper (should be HIGH): {sim_ab:.4f}")
print(f"Transformer paper <-> CRISPR paper (should be LOW): {sim_ac:.4f}")
print(f"BERT paper <-> CRISPR paper (should be LOW):        {sim_bc:.4f}")
assert sim_ab > sim_ac and sim_ab > sim_bc, "Related NLP papers did not score more similar than the unrelated biology paper"

print("OK: ONNX export is valid and semantically meaningful.")
