#!/usr/bin/env bash
# Trains the dictionaries used by the benchmark.
#
#   PHP_CORPUS=/usr/share/php PHP_PROJECT=/var/www/dev_new_erp ./benchmark/train-dictionaries.sh
#
# Public dictionaries (Kotlin, PHP, mixed) are written to benchmark/dicts.
# Project dictionaries are written to benchmark/dicts-local, which is git-ignored: they can contain fragments of
# the project's code and must not be committed.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PHP_CORPUS=${PHP_CORPUS:-/usr/share/php}
PHP_PROJECT=${PHP_PROJECT:-/var/www/dev_new_erp}

LIB_DIR=$(mktemp -d "${TMPDIR:-/tmp}/kotlin-editor-libs.XXXXXX")
trap 'rm -rf "$LIB_DIR"' EXIT

jars=()
for pattern in \
    'kotlin-stdlib-*-sources.jar' \
    'kotlinx-coroutines-core-jvm-*-sources.jar' \
    'kotlinx-serialization-core-jvm-*-sources.jar' \
    'kotlinx-serialization-json-jvm-*-sources.jar' \
    'kotlinx-io-core-jvm-*-sources.jar'; do
    jar=$(find "$HOME/.gradle/caches/modules-2/files-2.1" -name "$pattern" 2>/dev/null | sort -V | tail -1 || true)
    if [ -n "$jar" ]; then jars+=("$jar"); fi
done

if [ ${#jars[@]} -eq 0 ]; then
    echo "No Kotlin source jars found in ~/.gradle/caches; run ./kotlin build first." >&2
    exit 1
fi

for jar in "${jars[@]}"; do
    unzip -q -o "$jar" '*.kt' -d "$LIB_DIR"
done

count=$(find "$LIB_DIR" -name '*.kt' | wc -l)
if [ "$count" -lt 11 ]; then
    echo "Only $count Kotlin library files extracted; need at least 11." >&2
    exit 1
fi

args=(
    --train
    "--repo=$ROOT"
    "--php=$PHP_CORPUS"
    "--kotlin-libs=$LIB_DIR"
    "--dict-dir=$ROOT/benchmark/dicts"
    "--local-dict-dir=$ROOT/benchmark/dicts-local"
)
if [ -d "$PHP_PROJECT" ]; then
    args+=("--php-project=$PHP_PROJECT")
else
    echo "PHP project $PHP_PROJECT not found; the PHP project dictionary will not be trained." >&2
fi

cd "$ROOT"
./kotlin run -m benchmark -- "${args[@]}"
