#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=''
REGISTRY=''
APPLY=false
IMAGE_KEYS=(BACKEND_IMAGE COCKPIT_IMAGE X_CONSOLE_IMAGE EGRESS_IMAGE)

usage() {
  cat <<'EOF'
Preview old Trade application images eligible for local deletion.

Usage: bash cleanup-images.sh --root /opt/trade --registry ccr.ccs.tencentyun.com/tiancia-trade [--apply]

Without --apply, only print eligible image tags. With --apply, remove them
without force. Keep current.env, previous.env, and every container's images.
Only backend/cockpit/x-console/egress in the supplied registry namespace,
trade/, and trade-local/ are eligible. No containers, volumes, or registry
images are deleted. Run after a successful deployment.
EOF
}

fail() { printf 'Image cleanup aborted: %s\n' "$*" >&2; exit 1; }
require_value() { [[ $# -ge 2 && -n $2 && $2 != --* ]] || fail "$1 requires a value"; }
while (($#)); do
  case "$1" in
    --root) require_value "$@"; ROOT_DIR=$2; shift 2 ;;
    --registry) require_value "$@"; REGISTRY=$2; shift 2 ;;
    --apply) APPLY=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) fail "Unknown argument: $1" ;;
  esac
done

ROOT_DIR=${ROOT_DIR%/}
REGISTRY=${REGISTRY%/}
[[ $ROOT_DIR =~ ^/[A-Za-z0-9_./-]+$ && $ROOT_DIR != *'/../'* && $ROOT_DIR != */.. && $ROOT_DIR != *'/./'* && $ROOT_DIR != */. ]] || fail 'Supply an absolute repository path without spaces or dot segments'
[[ $REGISTRY =~ ^[a-z0-9][a-z0-9._:/-]*[a-z0-9]$ && $REGISTRY != *://* ]] || fail 'Supply a Docker registry namespace without a URL scheme'
command -v docker >/dev/null || fail 'docker is required'
command -v flock >/dev/null || fail 'flock is required'
RECORD_DIR="$ROOT_DIR/deploy/releases"
[[ -d $RECORD_DIR ]] || fail 'Release record directory does not exist'

# Re-read records under the same lock as release.sh, including after another
# operator deploys between this workflow's deployment and cleanup.
exec 9>"$RECORD_DIR/.release.lock"
flock -n 9 || fail 'Another deployment or cleanup is running; no images removed'

declare -A keep_refs=() keep_ids=() seen_refs=()
read_manifest() {
  local file=$1 line key value
  local -A values=()
  [[ -f $file ]] || fail "Missing $file; no images removed"
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    [[ $line =~ ^[[:space:]]*(#.*)?$ ]] && continue
    [[ $line =~ ^([A-Z_]+)=([A-Za-z0-9][A-Za-z0-9._:/@+-]*)$ ]] || fail "Invalid manifest line in $file"
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    case "$key" in
      BACKEND_IMAGE|COCKPIT_IMAGE|X_CONSOLE_IMAGE|EGRESS_IMAGE|RELEASE_VERSION) ;;
      *) fail "Unexpected manifest key in $file" ;;
    esac
    [[ ! -v values[$key] && $value != *://* ]] || fail "Invalid or duplicate manifest value in $file"
    if [[ $key == RELEASE_VERSION ]]; then
      [[ $value =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]] || fail "Invalid release version in $file"
    fi
    values[$key]=$value
  done < "$file"
  for key in "${IMAGE_KEYS[@]}"; do
    [[ -n ${values[$key]:-} ]] || fail "Missing $key in $file"
    keep_refs[${values[$key]}]=true
  done
}

read_manifest "$RECORD_DIR/current.env"
read_manifest "$RECORD_DIR/previous.env"

image_id() {
  local id
  id=$(docker image inspect --format '{{.Id}}' "$1") || fail "Cannot inspect protected/candidate image: $1"
  [[ $id =~ ^sha256:[a-f0-9]{64}$ ]] || fail "Unexpected image ID for $1"
  printf '%s\n' "$id"
}

# Resolve protected references before considering any deletion. Aliases of the
# same image must be protected too, even if the alias itself is an older tag.
for reference in "${!keep_refs[@]}"; do
  id=$(image_id "$reference") || exit 1
  keep_ids[$id]=true
done

container_ids=$(docker container ls -aq) || fail 'Cannot list containers; no images removed'
while IFS= read -r container_id; do
  [[ -n $container_id ]] || continue
  container_image=$(docker container inspect --format '{{.Image}} {{.Config.Image}}' "$container_id") || fail "Cannot inspect container $container_id; no images removed"
  read -r id reference extra <<< "$container_image"
  [[ $id =~ ^sha256:[a-f0-9]{64}$ && -n $reference && -z $extra ]] || fail "Unexpected image data for container $container_id"
  keep_ids[$id]=true
  keep_refs[$reference]=true
done <<< "$container_ids"

is_trade_repository() {
  local repository=$1 namespace service
  for namespace in "$REGISTRY" trade trade-local; do
    for service in backend cockpit x-console egress; do
      [[ $repository != "$namespace/$service" ]] || return 0
    done
  done
  return 1
}

# Capture output instead of using process substitution so a failed Docker
# query cannot silently turn into an empty protection/candidate list.
image_rows=$(docker image ls --no-trunc --format '{{.Repository}}:{{.Tag}} {{.ID}}') || fail 'Cannot list images; no images removed'
candidates=()
while read -r reference listed_id extra; do
  [[ -n $reference ]] || continue
  [[ -n $listed_id && -z $extra ]] || fail 'Unexpected image listing; no images removed'
  repository=${reference%:*}
  tag=${reference##*:}
  [[ $repository != '<none>' && $tag != '<none>' ]] || continue
  is_trade_repository "$repository" || continue
  [[ ! -v keep_refs[$reference] && ! -v seen_refs[$reference] ]] || continue
  seen_refs[$reference]=true
  id=$(image_id "$reference") || exit 1
  [[ ! -v keep_ids[$id] ]] || continue
  candidates+=("$reference")
done <<< "$image_rows"

# All records and Docker queries have succeeded before the first removal.
for reference in "${candidates[@]}"; do
  if [[ $APPLY == true ]]; then
    printf 'Removing old Trade image: %s\n' "$reference"
    docker image rm "$reference" || fail "Could not remove $reference; remaining images left untouched"
  else
    printf 'Would remove old Trade image: %s\n' "$reference"
  fi
done
if [[ $APPLY == true ]]; then
  printf 'IMAGE_CLEANUP_OK: removed %s old image tag(s).\n' "${#candidates[@]}"
else
  printf 'IMAGE_CLEANUP_DRY_RUN: %s eligible image tag(s); none removed.\n' "${#candidates[@]}"
fi
