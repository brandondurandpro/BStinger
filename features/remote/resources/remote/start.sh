#!/bin/sh
# Starts the per-user Kotlin Editor server and waits until it writes its state file.
# The client runs it over SSH, on every connection and whenever the daemon stops answering: it is idempotent, so
# reconnecting never starts a second daemon. A running daemon of the same protocol is reused, whatever bundle it
# runs from (another build of the IDE): its terminals and agent conversations go on. A daemon of another protocol
# is replaced, but while it still has sessions only with --replace (the user agreed): otherwise this script prints
# `in-use sessions=<n> version=<v> protocol=<p>` and exits with 3, and the IDE asks.
# Bundles are installed side by side, one directory per build; one nobody used for a week is removed.
set -eu

REPLACE=0
if [ "${1:-}" = "--replace" ]; then
    REPLACE=1
fi

D=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
H=${HOME:?}
S="$H/.kotlin-editor/server"
STATE="$S/state.properties"
TOKEN="$S/token"
LOG="$S/server.log"
LOCK="$S/start.lock"
mkdir -p "$S"
chmod 700 "$S" 2>/dev/null || true
umask 077

# Id of this bundle, written by the installer; the running daemon reports its own in the state file.
BUILD=""
if [ -f "$D/BUILD" ]; then
    BUILD=$(cat "$D/BUILD")
fi
# Protocol this bundle speaks, written by the installer too (empty for a bundle installed before it was).
PROTOCOL=""
if [ -f "$D/PROTOCOL" ]; then
    PROTOCOL=$(cat "$D/PROTOCOL")
fi

# One start at a time: two IDE windows connecting together must not start two daemons.
i=0
while ! mkdir "$LOCK" 2>/dev/null; do
    # A lock older than a minute was left by a start that got killed (an SSH link cut mid-way).
    if [ -n "$(find "$LOCK" -prune -mmin +1 2>/dev/null)" ]; then
        rmdir "$LOCK" 2>/dev/null || true
        continue
    fi
    i=$((i + 1))
    if [ "$i" -ge 600 ]; then
        echo "Another start of the server holds $LOCK" >&2
        exit 1
    fi
    sleep 0.1
done
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT
trap 'exit 1' INT TERM HUP

prop() {
    sed -n "s/^$1=//p" "$STATE" 2>/dev/null | tail -n 1
}

# True when process $1 is alive and is the daemon of this state file (a pid can be reused after a reboot).
alive() {
    kill -0 "$1" 2>/dev/null || return 1
    ARGS=$(ps -p "$1" -o args= 2>/dev/null) || return 0
    case "$ARGS" in
        *"$STATE"*) return 0 ;;
        *) return 1 ;;
    esac
}

# The bundles of other builds, unused for a week, except the one the running daemon was started from. Using a
# bundle touches its BUILD file.
prune() {
    touch "$D/BUILD" 2>/dev/null || true
    RUNNING=$(prop build)
    for X in "$S"/*/; do
        X=${X%/}
        if [ "$X" = "$D" ] || [ ! -f "$X/BUILD" ] || [ "$(cat "$X/BUILD")" = "$RUNNING" ]; then
            continue
        fi
        if [ -n "$(find "$X/BUILD" -mtime +7 2>/dev/null)" ]; then
            rm -rf "$X"
        fi
    done
}

# Whether the running daemon can serve this IDE: the same protocol (or, for a bundle that does not say which
# protocol it speaks, the same bundle).
compatible() {
    if [ -n "$PROTOCOL" ]; then
        [ "$(prop protocol)" = "$PROTOCOL" ]
    else
        [ "$(prop build)" = "$BUILD" ]
    fi
}

if [ -s "$STATE" ]; then
    PID=$(prop pid)
    if [ -n "$PID" ] && alive "$PID"; then
        if compatible; then
            prune
            exit 0
        fi
        # A daemon of another protocol. Unknown sessions (a daemon too old to count them) count as live ones.
        SESSIONS=$(prop sessions)
        if [ "$REPLACE" != 1 ] && [ "${SESSIONS:-unknown}" != 0 ]; then
            echo "in-use sessions=${SESSIONS:-unknown} version=$(prop version) protocol=$(prop protocol)"
            exit 3
        fi
        # Nobody uses it, or the user agreed to end its sessions: stop it.
        kill "$PID" 2>/dev/null || true
        j=0
        while alive "$PID" && [ "$j" -lt 100 ]; do
            sleep 0.1
            j=$((j + 1))
        done
        if alive "$PID"; then
            kill -9 "$PID" 2>/dev/null || true
        fi
    fi
    rm -f "$STATE"
fi

if [ ! -s "$TOKEN" ]; then
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32 > "$TOKEN"
    else
        od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$TOKEN"
    fi
    chmod 600 "$TOKEN"
fi

# Java names files in the charset of the locale, and an SSH session often has none (LANG unset: ASCII): accented
# names would then be unreadable. Take a UTF-8 locale the machine has.
if [ "$(locale charmap 2>/dev/null)" != "UTF-8" ]; then
    for L in C.UTF-8 C.utf8 en_US.UTF-8 en_US.utf8; do
        if [ "$(LC_ALL=$L locale charmap 2>/dev/null)" = "UTF-8" ]; then
            export LC_ALL="$L"
            break
        fi
    done
fi

# The server is either a native binary (a bundle built with GraalVM: no Java on the machine, less memory) or a jar
# run by the runtime of the bundle.
if [ -x "$D/kotlin-editor-server" ]; then
    SERVER="$D/kotlin-editor-server"
    set --
else
    SERVER="$D/runtime/bin/java"
    if [ ! -x "$SERVER" ]; then
        SERVER=java
    fi
    if [ -f "$D/serverApp.jar" ]; then
        set -- -jar "$D/serverApp.jar"
    elif [ -f "$D/classpath" ]; then
        # Development and end-to-end test bundle: the classes are already on this machine.
        set -- -cp "$(cat "$D/classpath")" org.example.kotlineditor.server.ServerMain
    else
        echo "No kotlin-editor-server, serverApp.jar or classpath in $D" >&2
        exit 1
    fi
    set -- --enable-native-access=ALL-UNNAMED "$@"
fi
set -- "$@" --host 127.0.0.1 --port 0 --data-dir "$H/.kotlin-editor" \
    --session-grace 1800 --token-file "$TOKEN" --state-file "$STATE"
if [ -n "$BUILD" ]; then
    set -- "$@" --build "$BUILD"
fi

if [ "${KOTLIN_EDITOR_SERVER_FOREGROUND:-0}" = "1" ]; then
    exec "$SERVER" "$@" >> "$LOG" 2>&1
fi

if command -v setsid >/dev/null 2>&1; then
    nohup setsid "$SERVER" "$@" >> "$LOG" 2>&1 < /dev/null &
else
    nohup "$SERVER" "$@" >> "$LOG" 2>&1 < /dev/null &
fi
STARTED=$!

i=0
while [ ! -s "$STATE" ]; do
    i=$((i + 1))
    if [ "$i" -ge 300 ]; then
        echo "The server did not start within 30 seconds; the end of $LOG says:" >&2
        tail -n 20 "$LOG" >&2 || true
        exit 1
    fi
    # The server died before listening (a missing library, a broken runtime): say so now rather than in 30 s.
    if [ "$i" -ge 10 ] && ! kill -0 "$STARTED" 2>/dev/null && [ ! -s "$STATE" ]; then
        echo "The server stopped while starting; the end of $LOG says:" >&2
        tail -n 20 "$LOG" >&2 || true
        exit 1
    fi
    sleep 0.1
done
prune
