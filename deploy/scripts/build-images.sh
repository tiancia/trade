#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
REGISTRY=${IMAGE_REGISTRY:-}
VERSION=${IMAGE_TAG:-}
PLATFORM=${BUILD_PLATFORM:-linux/amd64}
OUTPUT_FILE="$ROOT_DIR/deploy/release.env"
BASE_FILE="$ROOT_DIR/deploy/release.env"
PUSH=false
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

while (($#)); do
  case "$1" in
    --registry) require_value "$@"; REGISTRY=$2; shift 2 ;;
    --tag) require_value "$@"; VERSION=$2; shift 2 ;;
    --platform) require_value "$@"; PLATFORM=$2; shift 2 ;;
    --output) require_value "$@"; OUTPUT_FILE=$2; shift 2 ;;
    --base) require_value "$@"; BASE_FILE=$2; shift 2 ;;
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
for index in "${!names[@]}"; do
  [[ -v selected[${names[$index]}] ]] || continue
  image="$REGISTRY/${names[$index]}:$VERSION"
  images+=("$image")
  references[${keys[$index]}]=$image
  if [[ ${names[$index]} == backend ]]; then
    docker build --platform "$PLATFORM" --tag "$image" --file "$ROOT_DIR/backend/Dockerfile" "$ROOT_DIR"
  else
    docker build --platform "$PLATFORM" --tag "$image" "$ROOT_DIR/${contexts[$index]}"
  fi
done

if [[ $PUSH == true ]]; then
  for image in "${images[@]}"; do
    docker push "$image"
  done
fi

mkdir -p -- "$(dirname -- "$OUTPUT_FILE")"
manifest_tmp=$(mktemp "$(dirname -- "$OUTPUT_FILE")/.release.XXXXXX")
trap 'rm -f -- "$manifest_tmp"' EXIT
{
  printf 'RELEASE_VERSION=%s\n' "$VERSION"
  for key in "${keys[@]}"; do
    printf '%s=%s\n' "$key" "${references[$key]}"
  done
} > "$manifest_tmp"
mv -f -- "$manifest_tmp" "$OUTPUT_FILE"
printf 'Built services: %s\n' "${TARGETS[*]}"
printf 'Images ready. Non-secret release manifest: %s\n' "$OUTPUT_FILE"
