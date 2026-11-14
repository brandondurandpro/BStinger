#!/bin/sh
# Builds a self-contained remote server bundle: build/remote/server-<os>-<arch>.tar.gz.
#
#   tools/remote-bundle.sh                              # the machine this runs on
#   tools/remote-bundle.sh --target linux-aarch64 --jdk /path/to/target/jdk
#
# On Linux x86_64 the bundle holds the server compiled to a native binary with GraalVM (see docs/NATIVE_SERVER.md:
# no Java on the host, less memory) and the POSIX start script. The other targets, and --jvm, hold the executable
# server jar and a jlink runtime (so the host needs no Java) instead. The ghostty bindings of every supported
# platform are resources of the server.
#
#   tools/remote-bundle.sh --jvm                        # the jar and a jlink runtime, even on Linux x86_64
#   tools/remote-bundle.sh --no-install                 # only build/remote/, not ~/.kotlin-editor/bundles/
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET=""
JDK=""
JLINK=""
JVM=0
INSTALL=1

while [ $# -gt 0 ]; do
    case "$1" in
        --target) TARGET=$2; shift 2 ;;
        --jdk) JDK=$2; shift 2 ;;
        --jlink) JLINK=$2; shift 2 ;;
        --jvm) JVM=1; shift ;;
        --no-install) INSTALL=0; shift ;;
        *) echo "Unknown option $1" >&2; exit 2 ;;
    esac
done

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

NATIVE=0
if [ "$TARGET" = "linux-x86_64" ] && [ "$(uname -s)-$(uname -m)" = "Linux-x86_64" ] && [ "$JVM" = 0 ]; then
    NATIVE=1
fi

VERSION=$(sed -n 's/.*APP_VERSION = "\([^"]*\)".*/\1/p' "$ROOT/protocol/src/rpc/Protocol.kt" | head -n 1)
if [ -z "$VERSION" ]; then
    echo "Cannot read the server version" >&2
    exit 1
fi

cd "$ROOT"
STAGE="$ROOT/build/remote/stage-$TARGET"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$ROOT/features/remote/resources/remote/start.sh" "$STAGE/start.sh"
chmod +x "$STAGE/start.sh"
printf '%s\n' "$VERSION" > "$STAGE/VERSION"

if [ "$NATIVE" = 1 ]; then
    echo "Packaging the native server $VERSION for $TARGET"
    ./native-server build
    BINARY=$(./native-server path)
    [ -x "$BINARY" ] || { echo "Missing $BINARY" >&2; exit 1; }
    cp "$BINARY" "$STAGE/kotlin-editor-server"
    chmod +x "$STAGE/kotlin-editor-server"
else
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

echo "Packaging the server $VERSION for $TARGET (jmods: $JDK)"
./kotlin package -m serverApp -f executable-jar -v release >/dev/null
JAR="$ROOT/build/tasks/_serverApp_executableJarJvm/serverApp-jvm-executable.jar"
[ -f "$JAR" ] || { echo "Missing $JAR" >&2; exit 1; }

cp "$JAR" "$STAGE/serverApp.jar"

"$JLINK" \
    --module-path "$JDK/jmods" \
    --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,jdk.unsupported,jdk.zipfs \
    --strip-debug --no-header-files --no-man-pages --compress=2 \
    --output "$STAGE/runtime"
fi

# ripgrep (the pinned release of server/src/ripgrep/RipgrepRelease.kt) next to the server: Find in Files then uses
# it at once, with no download from the host. The server checks its SHA-256 and downloads one if it does not match.
case "$TARGET" in
    linux-x86_64) RG_TARGET=x86_64-unknown-linux-musl ;;
    linux-aarch64) RG_TARGET=aarch64-unknown-linux-musl ;;
    macos-x86_64) RG_TARGET=x86_64-apple-darwin ;;
    macos-aarch64) RG_TARGET=aarch64-apple-darwin ;;
    *) RG_TARGET="" ;;
esac
if [ -n "$RG_TARGET" ]; then
    RELEASE="$ROOT/server/src/ripgrep/RipgrepRelease.kt"
    RG_VERSION=$(sed -n 's/.*const val VERSION = "\([^"]*\)".*/\1/p' "$RELEASE")
    RG_ARCHIVE_SHA=$(grep -A2 "\"$RG_TARGET\"" "$RELEASE" | sed -n '2s/[^"]*"\([0-9a-f]*\)".*/\1/p')
    RG_BINARY_SHA=$(grep -A3 "\"$RG_TARGET\"" "$RELEASE" | sed -n '3s/[^"]*"\([0-9a-f]*\)".*/\1/p')
    RG_NAME="ripgrep-$RG_VERSION-$RG_TARGET"
    RG_CACHE="${KOTLIN_EDITOR_CACHE:-$HOME/.cache/kotlin-editor}/ripgrep"
    mkdir -p "$RG_CACHE"
    if [ ! -f "$RG_CACHE/$RG_NAME.tar.gz" ]; then
        curl -fsSL -o "$RG_CACHE/$RG_NAME.tar.gz" \
            "https://github.com/BurntSushi/ripgrep/releases/download/$RG_VERSION/$RG_NAME.tar.gz"
    fi
    sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi; }
    [ "$(sha256 "$RG_CACHE/$RG_NAME.tar.gz" | cut -d' ' -f1)" = "$RG_ARCHIVE_SHA" ] || {
        rm -f "$RG_CACHE/$RG_NAME.tar.gz"
        echo "ripgrep archive does not match its pinned SHA-256" >&2
        exit 1
    }
    mkdir -p "$STAGE/ripgrep"
    tar -xzf "$RG_CACHE/$RG_NAME.tar.gz" -C "$STAGE/ripgrep" --strip-components=1 "$RG_NAME/rg"
    [ "$(sha256 "$STAGE/ripgrep/rg" | cut -d' ' -f1)" = "$RG_BINARY_SHA" ] || {
        echo "rg does not match its pinned SHA-256" >&2
        exit 1
    }
    chmod +x "$STAGE/ripgrep/rg"
fi

mkdir -p "$ROOT/build/remote"
OUT="$ROOT/build/remote/server-$TARGET.tar.gz"
rm -f "$OUT"
tar -czf "$OUT" -C "$STAGE" .
rm -rf "$STAGE"
echo "Wrote $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"

# The IDE of this machine installs the bundle of ~/.kotlin-editor/bundles (see RemoteBundle): a stale copy there
# would keep installing an old server, so the new bundle replaces it. --no-install leaves it alone.
if [ "$INSTALL" = 1 ]; then
    BUNDLES="${HOME:?}/.kotlin-editor/bundles"
    mkdir -p "$BUNDLES"
    cp "$OUT" "$BUNDLES/server-$TARGET.tar.gz"
    echo "Installed in $BUNDLES/server-$TARGET.tar.gz"
fi
