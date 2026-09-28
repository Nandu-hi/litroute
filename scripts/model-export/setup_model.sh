#!/usr/bin/env bash
# One-time setup: exports the SPECTER2 model to ONNX for LitRoute's M5
# relevance scorer and installs it at ~/.litroute/models (override with
# SPECTER2_OUT_DIR). Downloads ~450MB of model weights from Hugging Face -
# needs network access, and only needs to be run once per machine.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv"

if [ ! -d "$VENV_DIR" ]; then
    echo "Creating venv at $VENV_DIR ..."
    python3 -m venv "$VENV_DIR"
fi

echo "Installing dependencies (CPU-only PyTorch + transformers/adapters/onnx) ..."
"$VENV_DIR/bin/pip" install --quiet --upgrade pip
"$VENV_DIR/bin/pip" install --quiet torch --index-url https://download.pytorch.org/whl/cpu
"$VENV_DIR/bin/pip" install --quiet -r "$SCRIPT_DIR/requirements.txt"

echo "Exporting SPECTER2 to ONNX ..."
"$VENV_DIR/bin/python" "$SCRIPT_DIR/export_specter2.py"

echo "Verifying the export ..."
"$VENV_DIR/bin/python" "$SCRIPT_DIR/verify_onnx.py"

OUT_DIR="${SPECTER2_OUT_DIR:-$HOME/.litroute/models}"
echo
echo "Done. Model files are at $OUT_DIR"
