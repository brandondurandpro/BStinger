#!/bin/sh
#
# Runs inside a test container of ../../linux-vm, from the checked-out snapshot: starts the display session, then
# does what the test-linux job of .github/workflows/ci.yml does (keep both in step).
#
#   run.sh test <x11|wayland> <app: yes|no> [kotlin test args...]
#   run.sh shell <x11|wayland>
#
set -eu

mode=$1
session=$2
shift 2
tmp=/tmp/runner
mkdir -p "$tmp"

case "$session" in
    x11)
        Xvfb :99 -screen 0 1600x1000x24 -nolisten tcp -ac > "$tmp/xvfb.log" 2>&1 &
        export DISPLAY=:99
        for _ in $(seq 1 30); do xdpyinfo > /dev/null 2>&1 && break; sleep 1; done
        ;;
    wayland)
        export XDG_RUNTIME_DIR="$tmp/xdg"
        mkdir -p "$XDG_RUNTIME_DIR"
        chmod 700 "$XDG_RUNTIME_DIR"
        # XWayland refuses to start (and Weston then crashes) without the shared socket directory.
        mkdir -p /tmp/.X11-unix
        chmod 1777 /tmp/.X11-unix
        weston --backend=headless-backend.so --xwayland --socket=wayland-ci \
            --width=1600 --height=1000 --idle-time=0 > "$tmp/weston.log" 2>&1 &
        export WAYLAND_DISPLAY=wayland-ci
        # AWT has no Wayland backend: the app goes through XWayland, which Weston composites as a Wayland surface.
        export DISPLAY=:0
        for _ in $(seq 1 30); do
            [ -S "$XDG_RUNTIME_DIR/wayland-ci" ] && break
            sleep 1
        done
        test -S "$XDG_RUNTIME_DIR/wayland-ci"
        # Connecting an X client starts XWayland.
        for _ in $(seq 1 30); do xdpyinfo -display :0 > /dev/null 2>&1 && break; sleep 1; done
        ;;
    *)
        echo "unknown session $session" >&2
        exit 2
        ;;
esac
xdpyinfo | grep -E "name of display|dimensions"

if [ "$mode" = shell ]; then
    echo "Ubuntu $(. /etc/os-release && echo "$VERSION_ID") · $session, in $(pwd): the container is deleted on exit."
    exec bash
fi

app=$1
shift

crash_logs() {
    for f in "$tmp"/weston.log "$tmp"/hs_err_*.log; do
        [ -f "$f" ] && { echo "----- $f"; tail -200 "$f"; }
    done
    true
}

./kotlin build || { crash_logs; exit 1; }
JAVA_TOOL_OPTIONS="-XX:ErrorFile=$tmp/hs_err_%p.log" ./kotlin test "$@" || { crash_logs; exit 1; }

# The tests render off-screen, so this is what proves the desktop app really starts in the session under test. A
# timeout (124) means it was still running when killed; any other exit code is a startup failure.
if [ "$app" = yes ]; then
    echo "Checking that the desktop app starts in the session (3 minutes)"
    set +e
    SKIKO_RENDER_API=SOFTWARE timeout -k 10 180 ./kotlin run -m desktopApp
    code=$?
    set -e
    if [ "$code" -ne 124 ]; then
        echo "desktopApp exited with $code before the timeout (expected 124)"
        exit 1
    fi
    echo "The desktop app stayed open."
fi
