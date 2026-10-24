#!/usr/bin/env bash
# Restore only into a newly created isolated disposable MySQL container.
set -euo pipefail
umask 077
BACKUP=''
IDENTITY=''
IMAGE=${RESTORE_MYSQL_IMAGE:-mysql:8.0}
container=''
work=''
fail() { printf 'Restore drill failed: %s\n' "$*" >&2; exit 1; }
value() { [[ $# -ge 2 && -n $2 && $2 != --* ]] || fail "$1 requires a value"; }
cleanup() {
  status=$?
  [[ -z $container ]] || docker rm -fv "$container" >/dev/null 2>&1 || true
  [[ -z $work ]] || rm -f -- "$work/mysql-password"
  [[ -z $work ]] || rmdir -- "$work" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT
while (($#)); do
  case "$1" in
    --backup) value "$@"; BACKUP=$2; shift 2 ;;
    --identity) value "$@"; IDENTITY=$2; shift 2 ;;
    --image) value "$@"; IMAGE=$2; shift 2 ;;
    --help|-h) printf '%s\n' 'Usage: restore-drill.sh --backup /absolute/mysql-*.sql.gz.age --identity /offline/age-key [--image mysql:8.0]'; exit 0 ;;
    *) fail "Unknown argument: $1" ;;
  esac
done
for tool in docker age gzip sha256sum mktemp; do command -v "$tool" >/dev/null || fail "$tool is required"; done
[[ -s $BACKUP && -s $BACKUP.sha256 && -s $IDENTITY ]] || fail 'Backup, checksum and decryption identity are required'
backup_dir=$(cd -- "$(dirname -- "$BACKUP")" && pwd)
backup_name=$(basename -- "$BACKUP")
[[ $backup_name =~ ^mysql-[0-9TZ]+-[0-9]+\.sql\.gz\.age$ ]] || fail 'Unexpected backup filename'
# Do not let an untrusted checksum file name another file.
read -r digest checksum_name extra < "$BACKUP.sha256"
[[ $digest =~ ^[a-f0-9]{64}$ && $checksum_name == "$backup_name" && -z ${extra:-} ]] || fail 'Invalid backup checksum'
printf '%s  %s\n' "$digest" "$backup_name" | (cd -- "$backup_dir" && sha256sum -c - >/dev/null)
docker image inspect "$IMAGE" >/dev/null || fail 'Load the approved MySQL image locally first; this script never pulls'
work=$(mktemp -d)
password=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
printf '%s' "$password" > "$work/mysql-password"
chmod 644 "$work/mysql-password"
unset password
# Capture the ID only after successful creation; cleanup can never target an existing container.
created_id=$(docker create --network none --label com.trade.restore-drill=true \
  --mount "type=bind,src=$work/mysql-password,dst=/run/secrets/mysql-password,readonly" \
  -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/mysql-password -e MYSQL_DATABASE=restore_drill \
  --memory 768m "$IMAGE")
[[ $created_id =~ ^[a-f0-9]{12,64}$ ]] || fail 'Docker did not return a valid newly-created container ID'
container=$created_id
docker start "$container" >/dev/null
ready=false
for ((attempt=0; attempt<60; attempt++)); do
  if docker exec "$container" sh -ec 'MYSQL_PWD=$(cat /run/secrets/mysql-password) mysql -u root -Nse "SELECT 1" >/dev/null' 2>/dev/null; then ready=true; break; fi
  sleep 2
done
[[ $ready == true ]] || fail 'Disposable MySQL did not become ready'
age --decrypt --identity "$IDENTITY" "$BACKUP" | gzip -dc \
  | docker exec -i "$container" sh -ec 'MYSQL_PWD=$(cat /run/secrets/mysql-password) exec mysql -u root restore_drill'
tables=$(docker exec "$container" sh -ec 'MYSQL_PWD=$(cat /run/secrets/mysql-password) mysql -u root restore_drill -Nse '\''SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = "BASE TABLE"'\''')
[[ $tables =~ ^[1-9][0-9]*$ ]] || fail 'No business tables restored'
docker exec "$container" sh -ec 'MYSQL_PWD=$(cat /run/secrets/mysql-password) exec mysqlcheck -u root --check restore_drill' >/dev/null
printf 'RESTORE_DRILL_OK %s tables=%s\n' "$backup_name" "$tables"
