#!/bin/bash
# Import trained pgx PolicyValueNet models from ../jass-models/ into
# src/main/resources/models/ (gitignored) as TF2 SavedModels for use
# by JassTheRipper's PgxPolicyValueEstimator.
#
# Prerequisites:
#   ../pgx/.venv        — JAX/Flax venv (Python 3.14)
#   ../pgx/.venv_tf     — TensorFlow venv (Python 3.13, tensorflow==2.20.x)
#   ../jass-models/     — directory with *.msgpack weight files
#
# See ../pgx/scripts/README.md for full setup instructions.
#
# Usage:
#   ./scripts/import_pgx_models.sh                    # import all models in ../jass-models/
#   ./scripts/import_pgx_models.sh pv_gen3_s128       # import one model by stem
set -e
cd "$(dirname "$0")/.."

PGX_DIR=../pgx
MODELS_DIR=../jass-models
EXTRACT="$PGX_DIR/.venv/bin/python $PGX_DIR/scripts/extract_pv_weights.py"
EXPORT="$PGX_DIR/.venv_tf/bin/python $PGX_DIR/scripts/export_pv_savedmodel.py"
OUT_BASE=src/main/resources/models

import_one() {
    local stem=$1
    local msgpack="$MODELS_DIR/${stem}.msgpack"
    local npz="/tmp/${stem}.npz"
    local out="$OUT_BASE/${stem}/export"

    if [[ ! -f "$msgpack" ]]; then
        echo "WARNING: $msgpack not found, skipping"
        return
    fi

    echo "=== Importing $stem ==="
    $EXTRACT --weights "$msgpack" --out "$npz"
    $EXPORT --weights "$npz" --out "$out"
    echo "Exported to $out"
    echo
}

if [[ $# -gt 0 ]]; then
    for stem in "$@"; do
        import_one "$stem"
    done
else
    for msgpack in "$MODELS_DIR"/*.msgpack; do
        stem=$(basename "$msgpack" .msgpack)
        import_one "$stem"
    done
fi

echo "Done. Models are in $OUT_BASE/ (gitignored; re-run this script after cloning)."
