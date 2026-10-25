#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
REGISTRY=${IMAGE_REGISTRY:-}
VERSION=${IMAGE_TAG:-}
PLATFORM=${BUILD_PLATFORM:-linux/amd64}
OUTPUT_FILE="$ROOT_DIR/deploy/release.env"
BASE_FILE="$ROOT_DIR/deploy/release.env"
PUSH=false
CACHE_DIR=''
cache_temporary=''
cache_backup=''
cache_destination=''
manifest_tmp=''
summary_started=false
TARGETS=()

usage() {
  cat <<'EOF'
Build versioned application images without reading deployment secrets.

Usage: bash deploy/scripts/build-images.sh --registry NAMESPACE --tag VERSION [options] [SERVICE ...]

  --registry NAMESPACE  Example: registry.example.com/trade (or IMAGE_REGISTRY)
  --tag VERSION         Immutable release tag (or IMAGE_TAG)
  --platform PLATFORM   Target platform; defaults to BUILD_PLATFORM or linux/amd64
  --output FILE         Image manifest; defaults to deploy/release.env
  --base FILE           Previous complete image manifest for partial builds;
                        defaults to deploy/release.env
  --push                Push selected images after every selected build succeeds
  --cache-dir DIR       Optional per-service local BuildKit cache (requires buildx);
                        disabled by default. Intended for restored runner caches.
  --help                Show this help

Services: backend cockpit x-console egress. Omit services to build all four.
Partial builds require a complete baseline manifest (for example, a copy of the
server's deploy/releases/current.env). Unselected image references are preserved
in the output manifest; only selected images are built and optionally pushed.

Docker performs Maven/npm packaging inside the Dockerfiles. No deployment .env
file, credentials or runtime environment is passed as a build argument.
Use a registry for normal updates; without --push, transfer images with docker
save/load and use release.sh deploy --no-pull on the server.
EOF
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
require_value() { [[ $# -ge 2 && -n $2 && $2 != --* ]] || fail "$1 requires a value"; }

filter_push_progress() {
  # Actions has no terminal. Keep readable byte progress without terminal escapes
  # or a new log line for every redraw of the same layer.
  stdbuf -o0 tr '\r' '\n' | awk '
    {
      gsub(/\033\[[0-9;?]*[A-Za-z]/, "")
      if ($0 ~ /^[[:space:]]*$/) next
      if ($2 == "Pushing") {
        pending[$1] = $0
        now = systime()
        if (($1 in last) && now - last[$1] < 10) next
        last[$1] = now
        delete pending[$1]
      } else {
        delete pending[$1]
      }
      print
      fflush()
    }
    END {
      for (layer in pending) print "Last observed progress: " pending[layer]
      fflush()
    }
  '
}

push_image() {
  local image=$1 push_command status
  if [[ ${GITHUB_ACTIONS:-} != true ]]; then
    docker push "$image"
    return
  fi
  command -v script >/dev/null || { printf 'Error: Actions image uploads require util-linux script\n' >&2; return 1; }
  command -v stdbuf >/dev/null || { printf 'Error: Actions image uploads require stdbuf\n' >&2; return 1; }
  # A pseudo-terminal makes Docker emit byte progress. The workflow's job timeout
  # bounds the run; a slow first upload must not fail at an arbitrary shorter limit.
  printf -v push_command 'docker push %q' "$image"
  printf 'Uploading %s (layer progress at most every 10 seconds)\n' "$image"
  if script --quiet --return --flush --command "$push_command" /dev/null | filter_push_progress; then
    return 0
  else
    status=$?
    if [[ $status == 137 ]]; then
      printf 'Error: Image upload was killed (exit 137): %s\n' "$image" >&2
    fi
    return "$status"
  fi
}

run_phase() {
  local phase=$1 service=$2 phase_started=$SECONDS status=0 outcome elapsed
  shift 2
  printf 'phase=%s service=%s status=started\n' "$phase" "$service"
  "$@" || status=$?
  elapsed=$((SECONDS - phase_started))
  outcome=success
  ((status == 0)) || outcome=failure
  printf 'phase=%s service=%s elapsed_seconds=%s status=%s exit_code=%s\n' \
    "$phase" "$service" "$elapsed" "$outcome" "$status"
  if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then
    if [[ $summary_started == false ]]; then
      printf '\n| Phase | Service | Seconds | Status |\n| --- | --- | ---: | --- |\n' >> "$GITHUB_STEP_SUMMARY" \
        || printf 'Warning: could not append build timing summary.\n' >&2
      summary_started=true
    fi
    printf '| %s | %s | %s | %s |\n' "$phase" "$service" "$elapsed" "$outcome" >> "$GITHUB_STEP_SUMMARY" \
      || printf 'Warning: could not append build timing summary.\n' >&2
  fi
  return "$status"
}

cache_error() { printf 'Error: Build cache rejected: %s\n' "$*" >&2; return 1; }

validate_cache_root() {
  local candidate=$1 ancestor canonical_home canonical_root
  [[ -n $candidate && $candidate != *','* && ! $candidate =~ [[:cntrl:]] ]] \
    || { cache_error 'use a nonempty path without commas or control characters'; return 1; }
  [[ $candidate == /* ]] || candidate="$PWD/$candidate"
  [[ $candidate != *'/../'* && $candidate != */.. && $candidate != *'/./'* && $candidate != */. ]] \
    || { cache_error 'dot path segments are not allowed'; return 1; }
  ancestor=${candidate%/}
  while [[ -n $ancestor && $ancestor != / ]]; do
    [[ ! -L $ancestor ]] || { cache_error 'symbolic links are not allowed'; return 1; }
    ancestor=$(dirname -- "$ancestor") || return
  done
  CACHE_DIR=$(realpath -m -- "$candidate") || return
  [[ $CACHE_DIR != / && $(dirname -- "$CACHE_DIR") != / ]] \
    || { cache_error 'filesystem roots and top-level directories are not cache locations'; return 1; }
  case "$CACHE_DIR" in
    /var/tmp|/var/cache|/var/lib|/usr/local|/usr/share|/usr/lib|/dev/shm)
      cache_error 'use a dedicated cache subdirectory instead of a shared system directory'; return 1 ;;
  esac
  canonical_root=$(realpath -m -- "$ROOT_DIR") || return
  canonical_home=$(realpath -m -- "${HOME:-/}") || return
  case "$canonical_root/" in "$CACHE_DIR/"*) cache_error 'the repository or one of its ancestors is not a cache directory'; return 1 ;; esac
  case "$canonical_home/" in "$CACHE_DIR/"*) cache_error 'the home directory or one of its ancestors is not a cache directory'; return 1 ;; esac
  [[ ! -e $CACHE_DIR || -d $CACHE_DIR ]] \
    || { cache_error 'the cache root must be a directory'; return 1; }
  mkdir -p -- "$CACHE_DIR" || return
  [[ $(realpath -e -- "$CACHE_DIR") == "$CACHE_DIR" && ! -L $CACHE_DIR ]] \
    || { cache_error 'the cache root changed during validation'; return 1; }
}

validate_service_cache() {
  local directory=$1 entry links populated=false
  [[ -e $directory || -L $directory ]] || return 0
  [[ -d $directory && ! -L $directory && $(realpath -e -- "$directory") == "$directory" ]] \
    || { cache_error 'service caches must be real directories'; return 1; }
  links=$(find -P "$directory" -type l -print -quit) || return
  [[ -z $links ]] || { cache_error 'symbolic links are not allowed inside a cache'; return 1; }
  for entry in "$directory"/* "$directory"/.[!.]* "$directory"/..?*; do
    [[ -e $entry || -L $entry ]] || continue
    populated=true
    case "${entry##*/}" in blobs|ingest|index.json|oci-layout) ;; *) cache_error 'service directory contains non-OCI files'; return 1 ;; esac
  done
  [[ $populated == true ]] || return 0
  [[ -f $directory/index.json && -d $directory/blobs ]] \
    || { cache_error 'existing cache is not an OCI index with a blob store'; return 1; }
  grep -Eq '"schemaVersion"[[:space:]]*:[[:space:]]*2' "$directory/index.json" \
    && grep -Eq '"manifests"[[:space:]]*:[[:space:]]*\[' "$directory/index.json" \
    || { cache_error 'existing cache index is not OCI schema version 2'; return 1; }
}

remove_owned_cache_temporary() {
  local directory=$1
  [[ -n $CACHE_DIR && ${directory%/*} == "$CACHE_DIR" && ${directory##*/} == .trade-cache-* \
    && ! -L $directory && $(realpath -m -- "$directory") == "$directory" ]] \
    || { cache_error 'temporary cache path escaped its verified root'; return 1; }
  rm -rf -- "$directory"
}

build_with_cache() {
  local service=$1 image=$2 context=$3 previous
  shift 3
  validate_cache_root "$CACHE_DIR" || return
  rm -f -- "$CACHE_DIR/.exported-$service" || return
  cache_destination="$CACHE_DIR/$service"
  validate_service_cache "$cache_destination" || return
  cache_temporary=$(mktemp -d "$CACHE_DIR/.trade-cache-$service-next.XXXXXX") || return
  local args=(buildx build --load --platform "$PLATFORM" --tag "$image")
  args+=("$@")
  if [[ -f $cache_destination/index.json ]]; then
    args+=(--cache-from "type=local,src=$cache_destination")
  fi
  args+=(--cache-to "type=local,dest=$cache_temporary,mode=max" --progress plain "$context")
  docker "${args[@]}" || return
  validate_service_cache "$cache_temporary" || return
  [[ -f $cache_temporary/index.json ]] \
    || { cache_error 'the successful build did not export an OCI cache'; return 1; }
  validate_service_cache "$cache_destination" || return
  if [[ -d $cache_destination ]]; then
    cache_backup=$(mktemp -d "$CACHE_DIR/.trade-cache-$service-old.XXXXXX") || return
    rmdir -- "$cache_backup" || return
    mv -T -- "$cache_destination" "$cache_backup" || return
  fi
  if mv -T -- "$cache_temporary" "$cache_destination"; then
    cache_temporary=''
  else
    previous=$?
    [[ -z $cache_backup ]] || mv -T -- "$cache_backup" "$cache_destination" || true
    return "$previous"
  fi
  if [[ -n $cache_backup ]]; then
    validate_service_cache "$cache_backup" || return
    remove_owned_cache_temporary "$cache_backup" || return
    cache_backup=''
  fi
  # Restored cache indexes alone do not prove this run exported a usable result.
  printf '%s\n' "$VERSION" > "$CACHE_DIR/.exported-$service"
}

cleanup_build_artifacts() {
  local status=$?
  [[ -z $manifest_tmp ]] || rm -f -- "$manifest_tmp"
  if [[ -n $cache_temporary ]]; then
    remove_owned_cache_temporary "$cache_temporary" || true
  fi
  if [[ -n $cache_backup && -d $cache_backup && ! -e $cache_destination ]]; then
    mv -T -- "$cache_backup" "$cache_destination" \
      || printf 'Warning: cache replacement failed; the previous cache remains in its backup directory.\n' >&2
  fi
  exit "$status"
}
trap cleanup_build_artifacts EXIT

while (($#)); do
  case "$1" in
    --registry) require_value "$@"; REGISTRY=$2; shift 2 ;;
    --tag) require_value "$@"; VERSION=$2; shift 2 ;;
    --platform) require_value "$@"; PLATFORM=$2; shift 2 ;;
    --output) require_value "$@"; OUTPUT_FILE=$2; shift 2 ;;
    --base) require_value "$@"; BASE_FILE=$2; shift 2 ;;
    --cache-dir) require_value "$@"; CACHE_DIR=$2; shift 2 ;;
    --push) PUSH=true; shift ;;
    --help|-h) usage; exit 0 ;;
    backend|cockpit|x-console|egress) TARGETS+=("$1"); shift ;;
    *) fail "Unknown argument: $1" ;;
  esac
done

REGISTRY=${REGISTRY%/}
[[ $REGISTRY =~ ^[a-z0-9][a-z0-9._:/-]*[a-z0-9]$ && $REGISTRY != *://* ]] || fail 'Supply a Docker registry namespace without a URL scheme'
[[ $VERSION =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || fail 'Supply a valid image tag of at most 128 characters'
[[ $VERSION != latest ]] || fail 'Use an explicit version instead of latest so releases can be rolled back'
[[ $PLATFORM =~ ^linux/(amd64|arm64)(/v[0-9]+)?$ ]] || fail 'Supported target platforms: linux/amd64 or linux/arm64'
command -v docker >/dev/null || fail 'docker is required'

names=(backend cockpit x-console egress)
contexts=(. frontend/trading-cockpit frontend/x-console deploy/egress)
keys=(BACKEND_IMAGE COCKPIT_IMAGE X_CONSOLE_IMAGE EGRESS_IMAGE)
if ((${#TARGETS[@]} == 0)); then
  TARGETS=("${names[@]}")
fi
declare -A selected=() references=()
for service in "${TARGETS[@]}"; do
  [[ ! -v selected[$service] ]] || fail "Duplicate service: $service"
  selected[$service]=true
done

if [[ -n $CACHE_DIR ]]; then
  for tool in realpath find grep; do command -v "$tool" >/dev/null || fail "$tool is required for local build caches"; done
  validate_cache_root "$CACHE_DIR" || exit $?
  for service in "${TARGETS[@]}"; do
    validate_service_cache "$CACHE_DIR/$service" || exit $?
  done
fi

# Keep a complete manifest for release.sh without inventing unbuilt image tags.
# Read it before building, including when --base and --output are the same file.
if ((${#TARGETS[@]} < ${#names[@]})); then
  [[ -f $BASE_FILE ]] || fail 'Partial builds require a complete image manifest; use --base FILE from a successful release, or build all services first'
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    [[ $line =~ ^[[:space:]]*(#.*)?$ ]] && continue
    [[ $line =~ ^([A-Z_]+)=([A-Za-z0-9][A-Za-z0-9._:/@+-]*)$ ]] || fail "Invalid image manifest line in $BASE_FILE"
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    case "$key" in BACKEND_IMAGE|COCKPIT_IMAGE|X_CONSOLE_IMAGE|EGRESS_IMAGE|RELEASE_VERSION) ;; *) fail "Unexpected manifest key: $key" ;; esac
    [[ ! -v references[$key] ]] || fail "Duplicate manifest key: $key"
    [[ $value != *://* ]] || fail "Image references cannot contain URL schemes: $key"
    if [[ $key == RELEASE_VERSION ]]; then
      [[ $value =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]] || fail 'Invalid RELEASE_VERSION in baseline manifest'
    fi
    references[$key]=$value
  done < "$BASE_FILE"
  for key in "${keys[@]}"; do
    [[ -n ${references[$key]:-} ]] || fail "Missing manifest key: $key"
  done
fi

images=()
image_services=()
for index in "${!names[@]}"; do
  [[ -v selected[${names[$index]}] ]] || continue
  image="$REGISTRY/${names[$index]}:$VERSION"
  images+=("$image")
  image_services+=("${names[$index]}")
  references[${keys[$index]}]=$image
  if [[ -n $CACHE_DIR ]]; then
    if [[ ${names[$index]} == backend ]]; then
      run_phase build "${names[$index]}" build_with_cache "${names[$index]}" "$image" "$ROOT_DIR" --file "$ROOT_DIR/backend/Dockerfile"
    else
      run_phase build "${names[$index]}" build_with_cache "${names[$index]}" "$image" "$ROOT_DIR/${contexts[$index]}"
    fi
  elif [[ ${names[$index]} == backend ]]; then
    run_phase build "${names[$index]}" docker build --platform "$PLATFORM" --tag "$image" --file "$ROOT_DIR/backend/Dockerfile" "$ROOT_DIR"
  else
    run_phase build "${names[$index]}" docker build --platform "$PLATFORM" --tag "$image" "$ROOT_DIR/${contexts[$index]}"
  fi
done

if [[ $PUSH == true ]]; then
  for index in "${!images[@]}"; do
    run_phase push "${image_services[$index]}" push_image "${images[$index]}"
  done
fi

mkdir -p -- "$(dirname -- "$OUTPUT_FILE")"
manifest_tmp=$(mktemp "$(dirname -- "$OUTPUT_FILE")/.release.XXXXXX")
{
  printf 'RELEASE_VERSION=%s\n' "$VERSION"
  for key in "${keys[@]}"; do
    printf '%s=%s\n' "$key" "${references[$key]}"
  done
} > "$manifest_tmp"
mv -f -- "$manifest_tmp" "$OUTPUT_FILE"
printf 'Built services: %s\n' "${TARGETS[*]}"
printf 'Images ready. Non-secret release manifest: %s\n' "$OUTPUT_FILE"
