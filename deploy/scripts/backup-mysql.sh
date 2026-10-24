#!/usr/bin/env bash
# Online InnoDB logical snapshot. No plaintext backup is written to disk.
set -euo pipefail
umask 077
ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
CONFIG_FILE=''
RELEASE_FILE=''
OUTPUT_DIR=''
RECIPIENTS=''
DESTINATION=''
SSH_KEY=''
KNOWN_HOSTS=''
HEARTBEAT_CONFIG=''
partial=''
fail() { printf 'Backup failed: %s\n' "$*" >&2; exit 1; }
value() { [[ $# -ge 2 && -n $2 && $2 != --* ]] || fail "$1 requires a value"; }
cleanup() { [[ -z $partial ]] || rm -f -- "$partial"; }
trap cleanup EXIT
while (($#)); do
  case "$1" in
    --root) value "$@"; ROOT_DIR=$2; shift 2 ;;
    --config) value "$@"; CONFIG_FILE=$2; shift 2 ;;
    --release) value "$@"; RELEASE_FILE=$2; shift 2 ;;
    --output) value "$@"; OUTPUT_DIR=$2; shift 2 ;;
    --recipients) value "$@"; RECIPIENTS=$2; shift 2 ;;
    --destination) value "$@"; DESTINATION=$2; shift 2 ;;
    --identity) value "$@"; SSH_KEY=$2; shift 2 ;;
    --known-hosts) value "$@"; KNOWN_HOSTS=$2; shift 2 ;;
    --heartbeat-config) value "$@"; HEARTBEAT_CONFIG=$2; shift 2 ;;
    --help|-h)
      printf '%s\n' 'Usage: backup-mysql.sh --recipients FILE --destination USER@HOST:/absolute/path --identity FILE --known-hosts FILE --heartbeat-config FILE [--root DIR] [--config FILE] [--release FILE] [--output DIR]'
      exit 0 ;;
    *) fail "Unknown argument: $1" ;;
  esac
done
ROOT_DIR=$(cd -- "$ROOT_DIR" && pwd)
CONFIG_FILE=${CONFIG_FILE:-$ROOT_DIR/deploy/.env}
RELEASE_FILE=${RELEASE_FILE:-$ROOT_DIR/deploy/releases/current.env}
OUTPUT_DIR=${OUTPUT_DIR:-$ROOT_DIR/backups}
for tool in docker age gzip ssh scp sha256sum flock curl; do command -v "$tool" >/dev/null || fail "$tool is required"; done
for input in "$CONFIG_FILE" "$RELEASE_FILE" "$RECIPIENTS" "$SSH_KEY" "$KNOWN_HOSTS" "$HEARTBEAT_CONFIG"; do
  [[ -s $input ]] || fail 'A required config, recipients, SSH identity, known-hosts or heartbeat file is missing/empty'
done
# Strictly constrain values used by the remote shell. No runtime env file is sourced.
[[ $DESTINATION =~ ^([A-Za-z0-9_][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9.-]*):(/[A-Za-z0-9_./-]+)$ ]] || fail 'Destination must be USER@HOST:/absolute/path using simple path characters'
remote=${BASH_REMATCH[1]}
remote_dir=${BASH_REMATCH[2]%/}
[[ $remote != *@backup-host && $remote != *@localhost && $remote != *@127.* && $remote != *@*.invalid ]] || fail 'Configure a separate real backup host'
[[ $remote_dir =~ ^(/[A-Za-z0-9_][A-Za-z0-9_.-]*)+$ ]] || fail 'Unsafe destination directory'
mkdir -p -- "$OUTPUT_DIR" "$ROOT_DIR/deploy/releases"
exec 9>"$ROOT_DIR/deploy/releases/.release.lock"
flock -n 9 || fail 'Another deployment or backup is running'
dc=(docker compose --project-directory "$ROOT_DIR" --env-file "$CONFIG_FILE" --env-file "$RELEASE_FILE" -f "$ROOT_DIR/compose.prod.yaml")
# The snapshot contract requires InnoDB. DDL must also be excluded during the dump.
non_transactional=$("${dc[@]}" exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -Nse '\''SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = "BASE TABLE" AND ENGINE <> "InnoDB"'\''')
[[ $non_transactional == 0 ]] || fail 'All business tables must be InnoDB'
backup_name="mysql-$(date -u +%Y%m%dT%H%M%SZ)-$$.sql.gz.age"
partial="$OUTPUT_DIR/$backup_name.partial"
"${dc[@]}" exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --triggers -u "$MYSQL_USER" "$MYSQL_DATABASE"' \
  | gzip -c | age --encrypt --recipients-file "$RECIPIENTS" > "$partial"
[[ -s $partial ]] || fail 'Empty encrypted backup'
digest=$(sha256sum -- "$partial")
digest=${digest%% *}
ssh_options=(-i "$SSH_KEY" -o BatchMode=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$KNOWN_HOSTS" -o ConnectTimeout=15)
ssh "${ssh_options[@]}" "$remote" "umask 077; mkdir -p '$remote_dir'"
scp "${ssh_options[@]}" -- "$partial" "$remote:$remote_dir/$backup_name.partial"
ssh "${ssh_options[@]}" "$remote" "set -eu; cd '$remote_dir'; printf '%s  %s\\n' '$digest' '$backup_name.partial' | sha256sum -c - >/dev/null; mv '$backup_name.partial' '$backup_name'; printf '%s  %s\\n' '$digest' '$backup_name' > '$backup_name.sha256'"
mv -- "$partial" "$OUTPUT_DIR/$backup_name"
partial=''
printf '%s  %s\n' "$digest" "$backup_name" > "$OUTPUT_DIR/$backup_name.sha256"
# Independent off-host heartbeat is sent only after off-host checksum verification.
# A curl config contains the private HTTPS endpoint; its contents are never printed.
curl --config "$HEARTBEAT_CONFIG" --proto '=https' --max-time 30 --fail --silent --show-error --output /dev/null
printf 'BACKUP_OFFSITE_OK %s\n' "$backup_name"
