#!/usr/bin/env bash
# Runs the zstd + dictionary benchmark and writes benchmark/report.md.
#
#   ./benchmark/run.sh
#   PHP_PROJECT=/path/to/php/project ITERATIONS=50 ./benchmark/run.sh
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PHP_CORPUS=${PHP_CORPUS:-/usr/share/php}
PHP_PROJECT=${PHP_PROJECT:-/var/www/dev_new_erp}
ITERATIONS=${ITERATIONS:-30}
OUT=${OUT:-$ROOT/benchmark/report.md}

if [ ! -d "$ROOT/benchmark/dicts" ] || [ -z "$(ls -A "$ROOT/benchmark/dicts")" ]; then
    echo "No dictionaries in benchmark/dicts; run ./benchmark/train-dictionaries.sh first." >&2
    exit 1
fi

args=(
    "--repo=$ROOT"
    "--php=$PHP_CORPUS"
    "--dict-dir=$ROOT/benchmark/dicts"
    "--local-dict-dir=$ROOT/benchmark/dicts-local"
    "--out=$OUT"
    "--iterations=$ITERATIONS"
)
if [ -d "$PHP_PROJECT" ]; then
    args+=("--php-project=$PHP_PROJECT")
fi

cd "$ROOT"
./kotlin run -m benchmark -- "${args[@]}"
