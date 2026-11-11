#!/usr/bin/env bash
# Dumps the memory of a running JVM and shows what consumes it.
#   tools/memory-dump.sh            lists the running JVMs
#   tools/memory-dump.sh <pid> [N]  histogram of live objects (top N, default 30) + native memory summary
#   HEAP_DUMP=1 tools/memory-dump.sh <pid>   also writes a .hprof, to open in Eclipse MAT or VisualVM
#   DETAIL='byte[]' tools/memory-dump.sh <pid>   also shows which objects hold the instances of a class (heap dump
#                                                + Eclipse MAT in headless mode, downloaded once into ~/.cache)
# Files go to build/memory-report/<pid>-<time>.*. Needs a JDK (jcmd).
set -eu

pid="${1:-}"
top="${2:-30}"

if [ -z "$pid" ]; then
    jcmd -l | grep -v 'JCmd\|jcmd'
    echo "Usage: $0 <pid> [N]"
    exit 0
fi

out="build/memory-report"
stamp="$(date +%H%M%S)"
base="$out/$pid-$stamp"
mkdir -p "$out"

echo "RSS: $(awk '/^VmRSS:/ {printf "%.0f MB", $2 / 1024}' "/proc/$pid/status" 2>/dev/null)"

# Live objects only: jcmd runs a full GC first.
jcmd "$pid" GC.class_histogram > "$base.histogram.txt"
echo
echo "Top $top classes by live bytes ($base.histogram.txt):"
printf '%12s %10s  %s\n' "MB" "instances" "class"
awk '$1 ~ /^[0-9]+:$/ && NF >= 4 { printf "%12.2f %10d  %s\n", $3 / 1048576, $2, $4 }' "$base.histogram.txt" | head -n "$top" || true
awk '$1 == "Total" { printf "\nTotal live: %.1f MB in %d objects\n", $3 / 1048576, $2 }' "$base.histogram.txt"

echo
echo "Native memory (heap, metaspace, code cache, threads…):"
native="$(jcmd "$pid" VM.native_memory summary 2>&1 || true)"
if grep -q 'committed=' <<<"$native"; then
    grep -E '^-|^Total' <<<"$native" | head -n 25
else
    echo "(start the JVM with -XX:NativeMemoryTracking=summary to see the native memory)"
fi

detail="${DETAIL:-}"
if [ "${HEAP_DUMP:-}" = "1" ] || [ -n "$detail" ]; then
    jcmd "$pid" GC.heap_dump "$(pwd)/$base.hprof" > /dev/null
    echo
    echo "Heap dump: $base.hprof (open it in Eclipse MAT: Leak Suspects and Dominator Tree)"
fi

if [ -n "$detail" ]; then
    mat_home="${XDG_CACHE_HOME:-$HOME/.cache}/kotlin-editor-tools/mat"
    mat_version="1.16.1"
    mat_url="https://www.eclipse.org/downloads/download.php?file=/mat/$mat_version/rcp/MemoryAnalyzer-$mat_version.20250109-linux.gtk.x86_64.zip&r=1"
    if [ ! -x "$mat_home/mat/ParseHeapDump.sh" ]; then
        echo "Downloading Eclipse MAT $mat_version (once)..."
        mkdir -p "$mat_home"
        curl -fsSL -o "$mat_home/mat.zip" "$mat_url"
        unzip -q -o "$mat_home/mat.zip" -d "$mat_home"
    fi
    # Immediate dominators: for each kind of object, how many instances of the class it holds and how many bytes.
    "$mat_home/mat/ParseHeapDump.sh" "$(pwd)/$base.hprof" "-command=immediate_dominators \"$detail\"" \
        org.eclipse.mat.api:query > "$base.mat.log" 2>&1 || { tail -n 20 "$base.mat.log"; exit 1; }
    report="$base.dominators"
    rm -rf "$report"
    mkdir -p "$report"
    unzip -q -o "${base}_Query.zip" -d "$report"
    echo
    echo "Who holds the instances of $detail (top $top, by bytes; $report/index.html has the full report):"
    python3 -I - "$report/index.html" "$top" <<'PY'
import html, re, sys

page = open(sys.argv[1], encoding="utf-8").read()
limit = int(sys.argv[2])
rows = re.findall(r"<tr[^>]*>(.*?)</tr>", page, re.S)
for index, row in enumerate(rows[: limit + 1]):
    cells = re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", row, re.S)
    text = [html.unescape(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", c))).strip() for c in cells]
    print("  ".join(text[i] for i in (0, 1, 4) if i < len(text)))
PY
    # MAT leaves big index files next to the dump.
    rm -f "$base".*.index "$base".index "$base.threads" "${base}_Query.zip"
fi
