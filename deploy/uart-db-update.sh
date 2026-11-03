#!/bin/sh
# Updates the web server or the collector from the latest GitHub release (or a given tag).
#
# Web server (system service, as root):
#   sudo uart-db-update            install the latest release if it is newer
#   sudo uart-db-update --check    only show installed and latest version
#   sudo uart-db-update v1.2.0     install exactly this release (also downgrade)
#   sudo uart-db-update --force    reinstall the latest release
# Collector on the Pi (user service, no sudo):
#   uart-db-update --collector [--check|--force|v1.2.0]
#
# Steps: download jar + SHA256SUMS, verify the checksum, keep the old jar as
# .bak, swap the jar, restart the service and wait until it is healthy (web
# server: /api/health answers; collector: running for STABLE_SECONDS without a
# restart). Otherwise the old jar is restored and the service restarted again.
#
# Install web server: sudo install -m 755 deploy/uart-db-update.sh /usr/local/bin/uart-db-update
# Install collector:  install -D -m 755 deploy/uart-db-update.sh ~/.local/bin/uart-db-update
# Settings (optional, shell syntax): /etc/default/uart-db-update for the web
# server, ~/.config/uart-db-update.conf for the collector, e.g. APP_DIR=/srv/app
set -eu

collector=0; check_only=0; force=0; tag=""
for arg in "$@"; do
    case "$arg" in
        --collector) collector=1 ;;
        --check) check_only=1 ;;
        --force) force=1 ;;
        v*) tag=$arg ;;
        *) echo "uart-db-update: unknown argument: $arg" >&2; exit 1 ;;
    esac
done

if [ "$collector" = 1 ]; then
    CONF=${CONF:-$HOME/.config/uart-db-update.conf}
else
    CONF=${CONF:-/etc/default/uart-db-update}
fi
[ -f "$CONF" ] && . "$CONF"

REPO=${REPO:-keep-hueggede/UART-DB-Collector}
if [ "$collector" = 1 ]; then
    APP_DIR=${APP_DIR:-$HOME/app}
    JAR=${JAR:-uart-db-collector.jar}
    SERVICE=${SERVICE:-uart-db-collector}
    HEALTH_URL=${HEALTH_URL:-}
    HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-180}   # ExecStartPre may wait for NTP
    STABLE_SECONDS=${STABLE_SECONDS:-20}
    LOCK=${LOCK:-${XDG_RUNTIME_DIR:-/tmp}/uart-db-update.lock}
    systemctl() { command systemctl --user "$@"; }
    logs_hint="tail -n 50 $APP_DIR/collector.log"
else
    APP_DIR=${APP_DIR:-/var/www/sh.rwx-dev.de}
    JAR=${JAR:-uart-db-webserver.jar}
    SERVICE=${SERVICE:-uart-db-webserver}
    HEALTH_URL=${HEALTH_URL:-auto}       # "auto": port from app.config, "" = only check the service
    HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-90}
    STABLE_SECONDS=${STABLE_SECONDS:-0}
    LOCK=${LOCK:-/run/uart-db-update.lock}
    logs_hint="journalctl -u $SERVICE"
fi

die() { echo "uart-db-update: $*" >&2; exit 1; }
log() { echo "uart-db-update: $*"; }

[ "$check_only" = 1 ] || [ "$collector" = 1 ] || [ "$(id -u)" = 0 ] || die "must run as root (sudo)"
[ -d "$APP_DIR" ] || die "$APP_DIR does not exist"

exec 9>"$LOCK"
flock -n 9 || die "another update is running"

installed=$(cat "$APP_DIR/.release" 2>/dev/null || echo "unknown")
latest=$(curl -fsSI "https://github.com/$REPO/releases/latest" \
    | sed -n 's#^[Ll]ocation: .*/releases/tag/\([^[:space:]]*\).*#\1#p' | tr -d '\r')
[ -n "$latest" ] || die "no release found for $REPO"
[ -n "$tag" ] || tag=$latest

log "$JAR installed: $installed, latest: $latest"
[ "$check_only" = 1 ] && exit 0
if [ "$tag" = "$installed" ] && [ "$force" = 0 ]; then
    log "already up to date"
    exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
base="https://github.com/$REPO/releases/download/$tag"
log "downloading $JAR $tag"
curl -fsSL -o "$tmp/$JAR" "$base/$JAR" || die "download of $base/$JAR failed"
curl -fsSL -o "$tmp/SHA256SUMS" "$base/SHA256SUMS" || die "download of SHA256SUMS failed"
(cd "$tmp" && grep "[ *]$JAR\$" SHA256SUMS | sha256sum -c --status -) || die "checksum mismatch - nothing changed"

if [ "$HEALTH_URL" = auto ]; then
    port=$(sed -n 's/^SERVER\.PORT=[[:space:]]*\([0-9]*\).*/\1/p' "$APP_DIR/app.config" 2>/dev/null | head -n 1)
    HEALTH_URL="http://127.0.0.1:${port:-8080}/api/health"
fi

restarts() { systemctl show -p NRestarts --value "$SERVICE" 2>/dev/null || echo 0; }

healthy() {
    i=0
    while [ "$i" -lt "$HEALTH_TIMEOUT" ]; do
        if systemctl is-active --quiet "$SERVICE" \
            && { [ -z "$HEALTH_URL" ] || curl -fs -o /dev/null --max-time 3 "$HEALTH_URL"; }; then
            [ "$STABLE_SECONDS" -gt 0 ] || return 0
            # a crashing jar is restarted by systemd and looks active in between
            before=$(restarts)
            sleep "$STABLE_SECONDS"
            systemctl is-active --quiet "$SERVICE" && [ "$(restarts)" = "$before" ]
            return
        fi
        sleep 3; i=$((i + 3))
    done
    return 1
}

target="$APP_DIR/$JAR"
[ -f "$target" ] && cp -p "$target" "$target.bak"
if [ "$(id -u)" = 0 ]; then
    owner=$(stat -c %U:%G "$APP_DIR")
    install -m 644 -o "${owner%%:*}" -g "${owner#*:}" "$tmp/$JAR" "$target.new"
else
    install -m 644 "$tmp/$JAR" "$target.new"
fi
mv -f "$target.new" "$target"

log "restarting $SERVICE"
systemctl restart "$SERVICE"
if healthy; then
    echo "$tag" > "$APP_DIR/.release"
    log "updated to $tag (previous jar: $target.bak)"
    exit 0
fi

[ -f "$target.bak" ] || die "$SERVICE did not come up and there is no backup - check: $logs_hint"
log "$SERVICE did not come up - rolling back to the previous jar"
cp -p "$target.bak" "$target"
systemctl restart "$SERVICE"
healthy || die "rollback did not come up either - check: $logs_hint"
die "update to $tag failed, previous version restored - check: $logs_hint"
