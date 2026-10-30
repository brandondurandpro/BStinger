#!/usr/bin/env bash
# Copies a trained dictionary into the compression module's resources.
#
#   ./compression/scripts/update-dictionary.sh
#   ./compression/scripts/update-dictionary.sh /path/to/project-64k.dict
#
# The default source is the mixed Kotlin+PHP dictionary trained by ./benchmark/train-dictionaries.sh.
# A project dictionary contains fragments of that project's code: do not commit it to a public repository.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SOURCE=${1:-$ROOT/benchmark/dicts/mixed-64k.dict}
TARGET=$ROOT/compression/resources/zstd/mixed-64k.dict

if [ ! -f "$SOURCE" ]; then
    echo "Dictionary not found: $SOURCE" >&2
    echo "Run ./benchmark/train-dictionaries.sh first, or pass a dictionary path." >&2
    exit 1
fi

size=$(wc -c <"$SOURCE")
if [ "$size" -ne 65536 ]; then
    echo "Warning: $SOURCE is $size bytes, expected 65536 (64 KiB)." >&2
fi

mkdir -p "$(dirname "$TARGET")"
cp "$SOURCE" "$TARGET"
echo "Copied $SOURCE -> $TARGET ($size bytes, sha256 $(sha256sum "$TARGET" | cut -d' ' -f1))"
echo "Run ./kotlin test -m compression to check the codec with the new dictionary."
