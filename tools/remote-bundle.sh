#!/bin/sh
# Builds a self-contained remote server bundle: build/remote/server-<os>-<arch>.tar.gz.
#
#   tools/remote-bundle.sh                              # the machine this runs on
#   tools/remote-bundle.sh --target linux-aarch64 --jdk /path/to/target/jdk
#
# The bundle holds the executable server jar, a jlink runtime (so the host needs no Java), the POSIX start
# script and the ghostty bindings for every supported platform (they are resources of the server jar).
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET=""
JDK=""
JLINK=""

while [ $# -gt 0 ]; do
    case "$1" in
        --target) TARGET=$2; shift 2 ;;
        --jdk) JDK=$2; shift 2 ;;
        --jlink) JLINK=$2; shift 2 ;;
        *) echo "Unknown option $1" >&2; exit 2 ;;
    esac
done

# jlink runs on this machine; --jdk only says where the target platform's jmods are (a full JDK or a jmods dir).
if [ -z "$JLINK" ]; then
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jlink" ]; then
        JLINK="$JAVA_HOME/bin/jlink"
    else
        JLINK=$(command -v jlink || true)
    fi
fi
if [ -z "$JLINK" ] || [ ! -x "$JLINK" ]; then
    echo "jlink not found: set JAVA_HOME or --jlink to a JDK 21+" >&2
    exit 1
fi
if [ -z "$JDK" ]; then
    JDK=$(CDPATH= cd -- "$(dirname -- "$JLINK")/.." && pwd)
fi
if [ ! -d "$JDK/jmods" ]; then
    echo "$JDK has no jmods directory: pass --jdk to a JDK for the target platform" >&2
    exit 1
fi

if [ -z "$TARGET" ]; then
    OS=$(uname -s)
    ARCH=$(uname -m)
    case "$OS" in
        Linux) OS=linux ;;
        Darwin) OS=macos ;;
        *) echo "Unsupported build host $OS" >&2; exit 1 ;;
    esac
    case "$ARCH" in
        x86_64|amd64) ARCH=x86_64 ;;
        aarch64|arm64) ARCH=aarch64 ;;
        *) echo "Unsupported build architecture $ARCH" >&2; exit 1 ;;
    esac
    TARGET="$OS-$ARCH"
fi

VERSION=$(sed -n 's/.*APP_VERSION = "\([^"]*\)".*/\1/p' "$ROOT/protocol/src@jvm/rpc/Protocol.kt" | head -n 1)
if [ -z "$VERSION" ]; then
    echo "Cannot read the server version" >&2
    exit 1
fi

echo "Packaging the server $VERSION for $TARGET (jmods: $JDK)"
cd "$ROOT"
./kotlin package -m serverApp -f executable-jar -v release >/dev/null
JAR="$ROOT/build/tasks/_serverApp_executableJarJvm/serverApp-jvm-executable.jar"
[ -f "$JAR" ] || { echo "Missing $JAR" >&2; exit 1; }

STAGE="$ROOT/build/remote/stage-$TARGET"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$JAR" "$STAGE/serverApp.jar"
cp "$ROOT/shared/resources/remote/start.sh" "$STAGE/start.sh"
chmod +x "$STAGE/start.sh"
printf '%s\n' "$VERSION" > "$STAGE/VERSION"

"$JLINK" \
    --module-path "$JDK/jmods" \
    --add-modules java.base,java.desktop,java.logging,java.management,java.naming,jdk.unsupported,jdk.zipfs \
    --strip-debug --no-header-files --no-man-pages --compress=2 \
    --output "$STAGE/runtime"

mkdir -p "$ROOT/build/remote"
OUT="$ROOT/build/remote/server-$TARGET.tar.gz"
rm -f "$OUT"
tar -czf "$OUT" -C "$STAGE" .
rm -rf "$STAGE"
echo "Wrote $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
