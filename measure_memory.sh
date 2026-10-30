#!/usr/bin/env bash
#
# Mesure la consommation mémoire du serveur et de l'application desktop.
#
# Usage: ./measure_memory.sh [duration_seconds]
#   duration_seconds: durée de mesure après démarrage (par défaut 30s)
#
# Variables d'environnement :
#   WARMUP_SECONDS   attente après l'apparition de la fenêtre avant de mesurer (défaut 10)
#   STARTUP_TIMEOUT  délai maximal de démarrage de chaque processus (défaut 300)
#
# Le script lance le serveur, puis l'app desktop connectée à ce serveur
# (--server), échantillonne leur mémoire toutes les secondes, puis affiche
# les statistiques (min, max, moyenne).
#
# `./kotlin run` démarre un JVM pour le CLI Kotlin, qui lance à son tour l'app
# dans un JVM enfant : c'est ce JVM enfant qui est mesuré, pas le CLI.
#
# Linux uniquement (/proc, setsid).
#
set -euo pipefail

# jstat et awk formatent les nombres selon la locale (virgule décimale en
# français), ce qui casserait le CSV.
export LC_ALL=C

cd "$(dirname "$0")"

DURATION="${1:-30}"
WARMUP_SECONDS="${WARMUP_SECONDS:-10}"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-300}"
SAMPLE_INTERVAL=1
TOKEN="memory-test"
RESULTS_DIR="build/memory-report"
mkdir -p "$RESULTS_DIR"

# Fichiers de résultats
SERVER_MEM_FILE="$RESULTS_DIR/server_memory.csv"
DESKTOP_MEM_FILE="$RESULTS_DIR/desktop_memory.csv"
SERVER_LOG="$RESULTS_DIR/server.log"
DESKTOP_LOG="$RESULTS_DIR/desktop.log"
SUMMARY_FILE="$RESULTS_DIR/summary.txt"

SERVER_CLI_PID=""
DESKTOP_CLI_PID=""

# Chaque `./kotlin run` est lancé dans sa propre session (setsid) : tuer le
# groupe de processus arrête à la fois le CLI et le JVM de l'app.
stop_group() {
    local pid=$1
    [ -n "$pid" ] || return 0
    kill -TERM -- "-$pid" 2>/dev/null || return 0
    for _ in $(seq 1 10); do
        kill -0 -- "-$pid" 2>/dev/null || return 0
        sleep 1
    done
    kill -KILL -- "-$pid" 2>/dev/null || true
}

cleanup() {
    stop_group "$DESKTOP_CLI_PID"
    stop_group "$SERVER_CLI_PID"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Le JVM de l'app est le dernier processus java parmi les descendants du CLI.
find_app_pid() {
    local root=$1 pid app="" queue
    queue=$(pgrep -P "$root" || true)
    while [ -n "$queue" ]; do
        set -- $queue
        pid=$1
        shift
        queue="$* $(pgrep -P "$pid" || true)"
        queue="${queue# }"
        [ "$(ps -o comm= -p "$pid" 2>/dev/null)" = "java" ] && app=$pid
    done
    # Le premier java est le CLI lui-même ; l'app est son enfant.
    [ -n "$app" ] && [ "$(ps -o comm= -p "$(ps -o ppid= -p "$app" | tr -d ' ')")" = "java" ] && echo "$app"
}

# Attend que le JVM de l'app soit lancé par le CLI, et affiche son PID.
wait_for_app_pid() {
    local cli_pid=$1 log=$2 app
    for _ in $(seq 1 "$STARTUP_TIMEOUT"); do
        if ! kill -0 "$cli_pid" 2>/dev/null; then
            echo "❌ Process exited during startup, see $log:" >&2
            tail -20 "$log" >&2
            return 1
        fi
        app=$(find_app_pid "$cli_pid" || true)
        if [ -n "$app" ]; then
            echo "$app"
            return 0
        fi
        sleep 1
    done
    echo "❌ Timed out after ${STARTUP_TIMEOUT}s waiting for the app JVM, see $log" >&2
    return 1
}

# Une ligne CSV : elapsed_s,rss_mb,heap_used_mb,heap_committed_mb,metaspace_mb
# La RSS (/proc) est la mémoire réelle du processus ; le détail du heap vient
# de jstat quand il est disponible (colonnes vides sinon).
sample_memory() {
    local pid=$1 elapsed=$2 rss_kb gc
    rss_kb=$(awk '/^VmRSS:/ {print $2}' "/proc/$pid/status" 2>/dev/null) || return 1
    [ -n "$rss_kb" ] || return 1

    gc=""
    if command -v jstat >/dev/null 2>&1; then
        # Colonnes lues par leur nom : leur ordre varie selon la version du JDK.
        gc=$(jstat -gc "$pid" 2>/dev/null | awk '
            NR == 1 { for (i = 1; i <= NF; i++) col[$i] = i; next }
            NR == 2 {
                used = $col["S0U"] + $col["S1U"] + $col["EU"] + $col["OU"]
                committed = $col["S0C"] + $col["S1C"] + $col["EC"] + $col["OC"]
                printf "%.1f,%.1f,%.1f", used / 1024, committed / 1024, $col["MU"] / 1024
            }') || gc=""
    fi
    [ -n "$gc" ] || gc=",,"

    awk -v t="$elapsed" -v rss="$rss_kb" -v gc="$gc" 'BEGIN { printf "%d,%.1f,%s\n", t, rss / 1024, gc }'
}

echo "============================================"
echo "  Memory Usage Measurement"
echo "============================================"
echo ""
echo "Duration: ${DURATION}s (warm-up ${WARMUP_SECONDS}s)"
echo "Sample interval: ${SAMPLE_INTERVAL}s"
echo "Results directory: $RESULTS_DIR"
echo ""

# Compiler d'abord : sinon le temps de build compte dans le démarrage.
echo "▶ Building..."
./kotlin build

CSV_HEADER="elapsed_s,rss_mb,heap_used_mb,heap_committed_mb,metaspace_mb"
echo "$CSV_HEADER" > "$SERVER_MEM_FILE"
echo "$CSV_HEADER" > "$DESKTOP_MEM_FILE"

# Lancer le serveur en premier, sur un port libre choisi par l'OS.
echo "▶ Starting server..."
setsid ./kotlin run -m serverApp -- --host 127.0.0.1 --port 0 --token "$TOKEN" \
    </dev/null >"$SERVER_LOG" 2>&1 &
SERVER_CLI_PID=$!
SERVER_PID=$(wait_for_app_pid "$SERVER_CLI_PID" "$SERVER_LOG")

# Attendre que le serveur écoute, et récupérer son port.
SERVER_PORT=""
for _ in $(seq 1 "$STARTUP_TIMEOUT"); do
    SERVER_PORT=$(sed -n 's/^Kotlin Editor server listening on .*:\([0-9]*\)$/\1/p' "$SERVER_LOG")
    [ -n "$SERVER_PORT" ] && break
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "❌ Server failed to start, see $SERVER_LOG:"
        tail -20 "$SERVER_LOG"
        exit 1
    fi
    sleep 1
done
if [ -z "$SERVER_PORT" ]; then
    echo "❌ Server did not report its port, see $SERVER_LOG"
    exit 1
fi
echo "  Server PID: $SERVER_PID (port $SERVER_PORT)"

# Lancer l'app desktop connectée à ce serveur : sans --server elle embarquerait
# son propre serveur, et sa mesure inclurait celle d'un second serveur.
echo "▶ Starting desktop app..."
SKIKO_RENDER_API="${SKIKO_RENDER_API:-SOFTWARE}" \
    setsid ./kotlin run -m desktopApp -- --server "127.0.0.1:$SERVER_PORT" --token "$TOKEN" \
    </dev/null >"$DESKTOP_LOG" 2>&1 &
DESKTOP_CLI_PID=$!
DESKTOP_PID=$(wait_for_app_pid "$DESKTOP_CLI_PID" "$DESKTOP_LOG")
echo "  Desktop PID: $DESKTOP_PID"

echo "▶ Warming up for ${WARMUP_SECONDS}s..."
sleep "$WARMUP_SECONDS"

for proc in "server:$SERVER_PID:$SERVER_LOG" "desktop:$DESKTOP_PID:$DESKTOP_LOG"; do
    IFS=: read -r name pid log <<<"$proc"
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "❌ The $name exited during warm-up, see $log:"
        tail -20 "$log"
        exit 1
    fi
done

echo ""
echo "▶ Measuring memory usage for ${DURATION}s..."
echo ""

ELAPSED=0
while [ "$ELAPSED" -lt "$DURATION" ]; do
    sample_memory "$SERVER_PID" "$ELAPSED" >> "$SERVER_MEM_FILE" || {
        echo "❌ The server exited during the measurement, see $SERVER_LOG"
        exit 1
    }
    sample_memory "$DESKTOP_PID" "$ELAPSED" >> "$DESKTOP_MEM_FILE" || {
        echo "❌ The desktop app exited during the measurement, see $DESKTOP_LOG"
        exit 1
    }
    sleep "$SAMPLE_INTERVAL"
    ELAPSED=$((ELAPSED + SAMPLE_INTERVAL))
done

echo "▶ Measurement complete."
echo ""

echo "▶ Stopping processes..."
cleanup
DESKTOP_CLI_PID=""
SERVER_CLI_PID=""
echo "  Processes stopped."
echo ""

# Min / max / moyenne de la RSS et du heap utilisé d'un CSV.
stats() {
    tail -n +2 "$1" | awk -F',' '
        NF > 0 {
            n++
            rss += $2
            if (n == 1 || $2 < rss_min) rss_min = $2
            if (n == 1 || $2 > rss_max) { rss_max = $2; rss_peak_at = $1 }
            if ($3 != "") {
                h++
                heap += $3
                if (h == 1 || $3 > heap_max) heap_max = $3
            }
            if ($5 != "") meta = $5
        }
        END {
            if (n == 0) { print "  no data collected"; exit }
            printf "  Samples:        %d\n", n
            printf "  RSS min:        %.1f MB\n", rss_min
            printf "  RSS max:        %.1f MB (at %ds)\n", rss_max, rss_peak_at
            printf "  RSS avg:        %.1f MB\n", rss / n
            if (h > 0) {
                printf "  Heap used max:  %.1f MB\n", heap_max
                printf "  Heap used avg:  %.1f MB\n", heap / h
                printf "  Metaspace:      %.1f MB\n", meta
            }
        }
    '
}

{
    echo "Memory Usage Report"
    echo "==================="
    echo ""
    echo "Date: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
    echo "Duration: ${DURATION}s (after ${WARMUP_SECONDS}s warm-up)"
    echo ""
    echo "Server:"
    stats "$SERVER_MEM_FILE"
    echo ""
    echo "Desktop:"
    stats "$DESKTOP_MEM_FILE"
    echo ""
    echo "Combined (RSS):"
    # Somme des deux processus à chaque instant, jointe sur elapsed_s.
    awk -F',' '
        FNR == 1 { next }
        NR == FNR { server[$1] = $2; next }
        ($1 in server) {
            combined = server[$1] + $2
            n++
            sum += combined
            if (n == 1 || combined > max) max = combined
        }
        END {
            if (n == 0) { print "  no data collected"; exit }
            printf "  Max combined:   %.1f MB\n", max
            printf "  Avg combined:   %.1f MB\n", sum / n
        }
    ' "$SERVER_MEM_FILE" "$DESKTOP_MEM_FILE"
} > "$SUMMARY_FILE"

echo "============================================"
echo "  Memory Usage Summary"
echo "============================================"
echo ""
cat "$SUMMARY_FILE"

echo ""
echo "Results saved to: $RESULTS_DIR/"
echo "  - server_memory.csv, desktop_memory.csv"
echo "  - server.log, desktop.log"
echo "  - summary.txt"
echo ""
echo "Done."
