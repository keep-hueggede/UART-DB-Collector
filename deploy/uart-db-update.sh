#!/bin/sh
# Updates the web server or the collector from the latest GitHub release (or a given tag).
#
# Web server (system service, as root):
#   sudo uart-db-update            install the latest release if it is newer
#   sudo uart-db-update --check    only show installed and latest version
#   sudo uart-db-update v1.2.0     install exactly this release (also downgrade)
#   sudo uart-db-update --force    reinstall the latest release
#   sudo uart-db-update --backup   only back up the database
#   sudo uart-db-update --no-backup  update without database backup
# Collector on the Pi (user service, no sudo):
#   uart-db-update --collector [--check|--force|v1.2.0]
#
# Steps: download jar + SHA256SUMS, verify the checksum, keep the old jar as
# .bak, swap the jar, restart the service and wait until it is healthy (web
# server: /api/health answers; collector: running for STABLE_SECONDS without a
# restart). Otherwise the old jar is restored and the service restarted again.
#
# Database backup (web server): before the new jar starts - and Hibernate may
# change the schema - the MySQL database from app.config is dumped with
# mysqldump to BACKUP_DIR (gzip, mode 600, newest BACKUP_KEEP kept). If the
# backup fails, nothing is updated. The password goes into a temporary option
# file, never onto the command line.
#
# Install web server: sudo install -m 755 deploy/uart-db-update.sh /usr/local/bin/uart-db-update
# Install collector:  install -D -m 755 deploy/uart-db-update.sh ~/.local/bin/uart-db-update
# Settings (optional, shell syntax): /etc/default/uart-db-update for the web
# server, ~/.config/uart-db-update.conf for the collector, e.g. APP_DIR=/srv/app
set -eu

collector=0; check_only=0; force=0; backup_only=0; no_backup=0; tag=""
for arg in "$@"; do
    case "$arg" in
        --collector) collector=1 ;;
        --backup) backup_only=1 ;;
        --no-backup) no_backup=1 ;;
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
    BACKUP=0   # the collector does not own the database
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
    BACKUP=${BACKUP:-1}
    BACKUP_DIR=${BACKUP_DIR:-/var/backups/uart-db}
    BACKUP_KEEP=${BACKUP_KEEP:-14}
    logs_hint="journalctl -u $SERVICE"
fi
[ "$no_backup" = 1 ] && BACKUP=0

die() { echo "uart-db-update: $*" >&2; exit 1; }
log() { echo "uart-db-update: $*"; }

[ "$check_only" = 1 ] || [ "$collector" = 1 ] || [ "$(id -u)" = 0 ] || die "must run as root (sudo)"
[ -d "$APP_DIR" ] || die "$APP_DIR does not exist"

exec 9>"$LOCK"
flock -n 9 || die "another update is running"

# Value of KEY from app.config (last one wins, like java.util.Properties)
config() {
    sed -n "s/^$1[[:space:]]*[=:][[:space:]]*//p" "$APP_DIR/app.config" 2>/dev/null | tail -n 1 | tr -d '\r'
}

# Dumps the database to BACKUP_DIR; prints the file name. Returns 1 on failure.
backup_db() {
    driver=$(config 'DB\.DRIVER')
    [ "$driver" = mysql ] || { log "database backup skipped (DB.DRIVER=${driver:-h2})" >&2; return 0; }
    command -v mysqldump >/dev/null || { echo "uart-db-update: mysqldump not found (apt install default-mysql-client)" >&2; return 1; }
    name=$(config 'DB\.NAME'); name=${name:-wetter}
    # option file syntax: "value" with \ and " escaped (printf: dash's echo would eat backslashes)
    opt() { printf '%s="%s"\n' "$1" "$(printf '%s' "$2" | sed 's/\\/\\\\/g; s/"/\\"/g')"; }

    old_umask=$(umask); umask 077
    mkdir -p "$BACKUP_DIR"
    cnf=$(mktemp)
    {
        printf '[client]\n'
        opt host "$(config 'DB\.HOST')"
        printf 'port=%s\n' "$(config 'DB\.PORT' | tr -dc 0-9)"
        opt user "$(config 'DB\.USER')"
        opt password "$(config 'DB\.PASSWORD')"
    } > "$cnf"
    file="$BACKUP_DIR/$name-$(date +%Y%m%d-%H%M%S)-$installed.sql"
    ok=0
    if mysqldump --defaults-extra-file="$cnf" --single-transaction --quick --no-tablespaces \
            --databases "$name" > "$file" 2> "$file.err" \
        && tail -n 1 "$file" | grep -q "Dump completed" \
        && gzip -f "$file"; then
        ok=1
    fi
    rm -f "$cnf"
    umask "$old_umask"
    if [ "$ok" = 0 ]; then
        echo "uart-db-update: database backup failed: $(head -c 300 "$file.err" 2>/dev/null)" >&2
        rm -f "$file" "$file.err"
        return 1
    fi
    rm -f "$file.err"
    # keep the newest BACKUP_KEEP dumps of this database
    ls -1t "$BACKUP_DIR/$name"-*.sql.gz 2>/dev/null | tail -n +$((BACKUP_KEEP + 1)) | while read -r old; do rm -f "$old"; done
    echo "$file.gz"
}

installed=$(cat "$APP_DIR/.release" 2>/dev/null || echo "unknown")

if [ "$backup_only" = 1 ]; then
    [ "$collector" = 0 ] || die "--backup is only available for the web server"
    dump=$(backup_db) || exit 1
    [ -z "$dump" ] || log "database saved to $dump"
    exit 0
fi
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

dump=""
if [ "$BACKUP" = 1 ]; then
    log "backing up the database"
    dump=$(backup_db) || die "update aborted because the backup failed - nothing changed (skip with --no-backup)"
    [ -z "$dump" ] || log "database saved to $dump"
fi
restore_hint=""
[ -z "$dump" ] || restore_hint=", restore the database if needed: zcat $dump | mysql -u <user> -p"

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
healthy || die "rollback did not come up either - check: $logs_hint$restore_hint"
die "update to $tag failed, previous version restored - check: $logs_hint$restore_hint"
