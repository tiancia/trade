#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
CONFIG_FILE=''
RELEASE_FILE=''
RECORD_DIR=''
ACTION=''
NO_PULL=false
TARGETS=()
IMAGE_KEYS=(BACKEND_IMAGE COCKPIT_IMAGE X_CONSOLE_IMAGE EGRESS_IMAGE)
WAIT_TIMEOUT=${DEPLOY_WAIT_TIMEOUT:-180}
ACCESS_MODE=${DEPLOY_ACCESS_MODE:-private}
manifest_tmp=''
record_tmp=''
mutation_started=false
phase=''
phase_started_at=0

usage() {
  cat <<'EOF'
Deploy one application instance, or restore a previous image release.

Usage: bash deploy/scripts/release.sh COMMAND [options] [SERVICE ...]

Commands:
  validate   Validate the manifest and Compose configuration; start nothing
  deploy     Pull first, stop selected containers, then recreate them and wait
  rollback   Deploy deploy/releases/previous.env (or an explicit --release)

Options:
  --root DIR      Existing deployment root; defaults to this script's repository
  --config FILE   Runtime secrets/config; defaults to deploy/.env
  --release FILE  Non-secret image manifest; deploy/validate: deploy/release.env
  --access MODE   private (default), or public with compose.public.yaml
  --no-pull       Require all needed images already loaded locally; never pull
  --help          Show this help

Services: backend cockpit x-console egress gateway.
Omit services for the initial/full deployment. Full deployment also starts
mysql/redis with --no-recreate. A selected-service update does not start or
change dependencies; deploy the full stack before using selected updates.

Private access uses only compose.prod.yaml. Public access adds compose.public.yaml
and requires its domain/TLS configuration. Pass --access public for every public
validate, deploy, and rollback command (or export DEPLOY_ACCESS_MODE=public).
The access mode is not stored in image manifests or read from the runtime config.

The script never sources or prints the runtime config, runs database migrations,
uses compose down, or removes volumes. Image manifests accept only four *_IMAGE
keys and an optional RELEASE_VERSION. Successful operations update release.env
and releases/current.env; rollback swaps current and previous, so it is repeatable.
If an update fails after stopping containers, inspect it and retry or rollback;
the script exits unsuccessfully and retains the last successful version record.
EOF
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
require_value() { [[ $# -ge 2 && -n $2 && $2 != --* ]] || fail "$1 requires a value"; }
phase_start() {
  phase=$1
  phase_started_at=$SECONDS
  printf 'DEPLOY_STAGE stage=%s event=start elapsed_seconds=0 exit_code=0\n' "$phase"
}
phase_complete() {
  printf 'DEPLOY_STAGE stage=%s event=complete elapsed_seconds=%s exit_code=0\n' \
    "$phase" "$((SECONDS - phase_started_at))"
  phase=''
}
cleanup() {
  local status=$?
  trap - EXIT
  if ((status != 0)) && [[ -n $phase ]]; then
    printf 'DEPLOY_STAGE stage=%s event=failed elapsed_seconds=%s exit_code=%s\n' \
      "$phase" "$((SECONDS - phase_started_at))" "$status" >&2 || true
  fi
  [[ -z $manifest_tmp ]] || rm -f -- "$manifest_tmp" || true
  [[ -z $record_tmp ]] || rm -f -- "$record_tmp" || true
  if ((status != 0)) && [[ $mutation_started == true ]]; then
    printf 'Deployment failed. Containers may be stopped or partially updated. Last successful image records were retained; inspect the stack, then retry or rollback.\n' >&2 || true
  fi
  exit "$status"
}
trap cleanup EXIT

while (($#)); do
  case "$1" in
    --root) require_value "$@"; ROOT_DIR=$2; shift 2 ;;
    --config) require_value "$@"; CONFIG_FILE=$2; shift 2 ;;
    --release) require_value "$@"; RELEASE_FILE=$2; shift 2 ;;
    --access) require_value "$@"; ACCESS_MODE=$2; shift 2 ;;
    --no-pull) NO_PULL=true; shift ;;
    --help|-h) usage; exit 0 ;;
    validate|deploy|rollback)
      [[ -z $ACTION ]] || fail 'Specify exactly one command'
      ACTION=$1; shift ;;
    --*) fail "Unknown option: $1" ;;
    *) TARGETS+=("$1"); shift ;;
  esac
done

phase_start validation
[[ -n $ACTION ]] || { usage >&2; exit 1; }
[[ -d $ROOT_DIR ]] || fail 'Deployment root directory does not exist'
ROOT_DIR=$(cd -- "$ROOT_DIR" && pwd -P)
[[ $ROOT_DIR != / ]] || fail 'Deployment root must not be the filesystem root'
[[ -d $ROOT_DIR/deploy ]] || fail 'deploy directory not found at deployment root'
[[ -f $ROOT_DIR/compose.prod.yaml ]] || fail 'compose.prod.yaml not found at deployment root'
CONFIG_FILE=${CONFIG_FILE:-"$ROOT_DIR/deploy/.env"}
RECORD_DIR="$ROOT_DIR/deploy/releases"
case "$ACCESS_MODE" in private|public) ;; *) fail 'Access mode must be private or public' ;; esac
[[ $WAIT_TIMEOUT =~ ^[1-9][0-9]*$ ]] || fail 'DEPLOY_WAIT_TIMEOUT must be a positive number of seconds'
for service in "${TARGETS[@]}"; do
  case "$service" in backend|cockpit|x-console|egress|gateway) ;; *) fail "Unsupported application service: $service" ;; esac
done
FULL_DEPLOY=false
if ((${#TARGETS[@]} == 0)); then
  FULL_DEPLOY=true
  TARGETS=(egress backend cockpit x-console gateway)
fi
if [[ -z $RELEASE_FILE ]]; then
  if [[ $ACTION == rollback ]]; then
    RELEASE_FILE="$RECORD_DIR/previous.env"
  else
    RELEASE_FILE="$ROOT_DIR/deploy/release.env"
  fi
fi
[[ -f $CONFIG_FILE ]] || fail "Runtime configuration not found: $CONFIG_FILE"
[[ -f $RELEASE_FILE ]] || fail "Image manifest not found: $RELEASE_FILE"
if [[ $ACCESS_MODE == public ]]; then
  [[ -f $ROOT_DIR/compose.public.yaml ]] || fail 'compose.public.yaml not found at repository root'
fi
command -v docker >/dev/null || fail 'docker is required'

# Never source an env file. Accept a deliberately narrow non-secret manifest.
read_manifest() {
  local file=$1 line key value
  local -n result=$2
  result=()
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    [[ $line =~ ^[[:space:]]*(#.*)?$ ]] && continue
    [[ $line =~ ^([A-Z_]+)=([A-Za-z0-9][A-Za-z0-9._:/@+-]*)$ ]] || fail "Invalid image manifest line in $file"
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    case "$key" in BACKEND_IMAGE|COCKPIT_IMAGE|X_CONSOLE_IMAGE|EGRESS_IMAGE|RELEASE_VERSION) ;; *) fail "Unexpected manifest key: $key" ;; esac
    [[ ! -v result[$key] ]] || fail "Duplicate manifest key: $key"
    [[ $value != *://* ]] || fail "Image references cannot contain URL schemes: $key"
    if [[ $key == RELEASE_VERSION ]]; then
      [[ $value =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]] || fail 'Invalid RELEASE_VERSION'
    fi
    result[$key]=$value
  done < "$file"
  for key in "${IMAGE_KEYS[@]}"; do
    [[ -n ${result[$key]:-} ]] || fail "Missing manifest key: $key"
  done
}

write_manifest() {
  local file=$1 key
  local -n values=$2
  {
    [[ -z ${values[RELEASE_VERSION]:-} ]] || printf 'RELEASE_VERSION=%s\n' "${values[RELEASE_VERSION]}"
    for key in "${IMAGE_KEYS[@]}"; do
      printf '%s=%s\n' "$key" "${values[$key]}"
    done
  } > "$file"
}

declare -A desired=() current=() activated=()
read_manifest "$RELEASE_FILE" desired
if [[ $ACTION != validate ]]; then
  command -v flock >/dev/null || fail 'flock is required (Ubuntu util-linux)'
  mkdir -p -- "$RECORD_DIR"
  exec 9>"$RECORD_DIR/.release.lock"
  flock -n 9 || fail 'Another deployment is already running'
fi
if [[ -f $RECORD_DIR/current.env ]]; then
  read_manifest "$RECORD_DIR/current.env" current
fi

# For a single-service release, preserve the successful images of other services.
for key in "${IMAGE_KEYS[@]}"; do
  activated[$key]=${desired[$key]}
  if [[ $FULL_DEPLOY == false && -n ${current[$key]:-} ]]; then
    activated[$key]=${current[$key]}
  fi
done
activated[RELEASE_VERSION]=${desired[RELEASE_VERSION]:-}
for service in "${TARGETS[@]}"; do
  case "$service" in
    backend) activated[BACKEND_IMAGE]=${desired[BACKEND_IMAGE]} ;;
    cockpit) activated[COCKPIT_IMAGE]=${desired[COCKPIT_IMAGE]} ;;
    x-console) activated[X_CONSOLE_IMAGE]=${desired[X_CONSOLE_IMAGE]} ;;
    egress) activated[EGRESS_IMAGE]=${desired[EGRESS_IMAGE]} ;;
  esac
done

manifest_tmp=$(mktemp "${TMPDIR:-/tmp}/trade-release.XXXXXX")
write_manifest "$manifest_tmp" activated
compose=(docker compose --project-directory "$ROOT_DIR" --env-file "$CONFIG_FILE" --env-file "$manifest_tmp" -f "$ROOT_DIR/compose.prod.yaml")
if [[ $ACCESS_MODE == public ]]; then
  compose+=(-f "$ROOT_DIR/compose.public.yaml")
fi
image_environment=()
for key in "${IMAGE_KEYS[@]}"; do
  image_environment+=("$key=${activated[$key]}")
done
compose_run() { env "${image_environment[@]}" "${compose[@]}" "$@"; }

compose_run config -q
phase_complete
if [[ $ACTION == validate ]]; then
  printf 'Compose configuration and image manifest are valid (%s access). No containers changed.\n' "$ACCESS_MODE"
  exit 0
fi

pull_targets=("${TARGETS[@]}")
if [[ $FULL_DEPLOY == true ]]; then
  pull_targets+=(mysql redis)
fi
# Complete every pull/image check before stopping or changing a container.
if [[ $NO_PULL == true ]]; then
  phase_start local_image_check
  image_list=$(compose_run config --images "${pull_targets[@]}")
  [[ -n $image_list ]] || fail 'Compose returned no required images'
  while IFS= read -r image; do
    [[ -z $image ]] || docker image inspect "$image" >/dev/null
  done <<< "$image_list"
else
  phase_start image_pull
  compose_run pull "${pull_targets[@]}"
fi
phase_complete

if ((${#current[@]})); then
  record_tmp=$(mktemp "$RECORD_DIR/.record.XXXXXX")
  write_manifest "$record_tmp" current
  cp -- "$record_tmp" "$RECORD_DIR/$(date -u +%Y%m%dT%H%M%SZ)-$$.env"
  mv -f -- "$record_tmp" "$RECORD_DIR/previous.env"
  record_tmp=''
fi
# Retain the requested images too, so a failed rollback can be retried explicitly
# even after previous.env has been refreshed to the last successful state.
record_tmp=$(mktemp "$RECORD_DIR/.record.XXXXXX")
write_manifest "$record_tmp" activated
mv -f -- "$record_tmp" "$RECORD_DIR/target-$(date -u +%Y%m%dT%H%M%SZ)-$$.env"
record_tmp=''

mutation_started=true
if [[ $FULL_DEPLOY == true ]]; then
  phase_start dependencies_start
  compose_run up -d --pull never --no-recreate --wait --wait-timeout "$WAIT_TIMEOUT" mysql redis
  phase_complete
fi
phase_start container_stop
compose_run stop "${TARGETS[@]}"
phase_complete
# Recreate selected applications so bind-mounted files replaced atomically on
# the host (SSH keys, certificates, gateway template) bind to their new inode.
phase_start container_start_and_health
compose_run up -d --pull never --no-deps --force-recreate --wait --wait-timeout "$WAIT_TIMEOUT" "${TARGETS[@]}"
phase_complete

phase_start release_record
record_tmp=$(mktemp "$RECORD_DIR/.record.XXXXXX")
write_manifest "$record_tmp" activated
mv -f -- "$record_tmp" "$RECORD_DIR/current.env"
record_tmp=''
record_tmp=$(mktemp "$ROOT_DIR/deploy/.release.XXXXXX")
write_manifest "$record_tmp" activated
mv -f -- "$record_tmp" "$ROOT_DIR/deploy/release.env"
record_tmp=''
mutation_started=false
phase_complete
printf '%s succeeded for: %s\n' "$ACTION" "${TARGETS[*]}"
printf 'Successful image record: %s/current.env\n' "$RECORD_DIR"
