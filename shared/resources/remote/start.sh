#!/bin/sh
# Starts the per-user Kotlin Editor server and waits until it writes its state file.
# The client runs it over SSH; it is idempotent, so reconnecting never starts a second daemon.
set -eu

D=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
H=${HOME:?}
S="$H/.kotlin-editor/server"
STATE="$S/state.properties"
TOKEN="$S/token"
LOG="$S/server.log"
mkdir -p "$S"
chmod 700 "$S" 2>/dev/null || true
umask 077

if [ -s "$STATE" ]; then
    PID=$(sed -n 's/^pid=\([0-9][0-9]*\)$/\1/p' "$STATE" | tail -n 1)
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        exit 0
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

JAVA="$D/runtime/bin/java"
if [ ! -x "$JAVA" ]; then
    JAVA=java
fi
if [ -f "$D/serverApp.jar" ]; then
    set -- -jar "$D/serverApp.jar"
elif [ -f "$D/classpath" ]; then
    # Development and end-to-end test bundle: the classes are already on this machine.
    set -- -cp "$(cat "$D/classpath")" org.example.kotlineditor.server.ServerMain
else
    echo "No serverApp.jar or classpath in $D" >&2
    exit 1
fi

FOREGROUND=0
if [ "${KOTLIN_EDITOR_SERVER_FOREGROUND:-0}" = "1" ]; then
    FOREGROUND=1
fi
if [ "$FOREGROUND" = "1" ]; then
    exec "$JAVA" --enable-native-access=ALL-UNNAMED "$@" --host 127.0.0.1 --port 0 \
        --data-dir "$H/.kotlin-editor" --session-grace 1800 --token-file "$TOKEN" --state-file "$STATE" >> "$LOG" 2>&1
fi

if command -v setsid >/dev/null 2>&1; then
    nohup setsid "$JAVA" --enable-native-access=ALL-UNNAMED "$@" --host 127.0.0.1 --port 0 \
        --data-dir "$H/.kotlin-editor" --session-grace 1800 --token-file "$TOKEN" --state-file "$STATE" >> "$LOG" 2>&1 < /dev/null &
else
    nohup "$JAVA" --enable-native-access=ALL-UNNAMED "$@" --host 127.0.0.1 --port 0 \
        --data-dir "$H/.kotlin-editor" --session-grace 1800 --token-file "$TOKEN" --state-file "$STATE" >> "$LOG" 2>&1 < /dev/null &
fi

i=0
while [ ! -s "$STATE" ]; do
    i=$((i + 1))
    if [ "$i" -ge 300 ]; then
        echo "The server did not start; see $LOG" >&2
        exit 1
    fi
    sleep 0.1
done
