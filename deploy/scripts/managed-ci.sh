#!/usr/bin/env bash
# Provider-neutral entry point for a managed Linux CI worker with Docker/buildx.
set +x
set -euo pipefail
umask 077

SCRIPT_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
SERVICE=${SERVICE:-backend}
BUILD_REQUESTED=${BUILD_REQUESTED:-true}
DEPLOY_REQUESTED=${DEPLOY_REQUESTED:-false}
ACCESS_MODE=${ACCESS_MODE:-private}
DEPLOY_ROOT=${DEPLOY_ROOT:-/opt/trade}
DEPLOY_PORT=${DEPLOY_PORT:-22}
CI_RUN_ID=${CI_RUN_ID:-${BUILD_NUMBER:-}}
OUTPUT_DIR=${OUTPUT_DIR:-"$PWD/.ci-output"}
BUILD_CACHE_DIR=${BUILD_CACHE_DIR:-}
WAIT_TIMEOUT=${DEPLOY_WAIT_TIMEOUT:-300}
REGISTRY_HOST=ccr.ccs.tencentyun.com
EXPECTED_REGISTRY=ccr.ccs.tencentyun.com/tiancia-trade
work_dir=''
work_parent=''
record_tmp=''
builder_name=''
builder_created=false
ssh_config=''
names=(backend cockpit x-console egress)
keys=(BACKEND_IMAGE COCKPIT_IMAGE X_CONSOLE_IMAGE EGRESS_IMAGE)
targets=()

usage() {
  cat <<'EOF'
Usage: bash deploy/scripts/managed-ci.sh

Environment:
  SERVICE                backend (default), cockpit, x-console, frontends, egress, all
  BUILD_REQUESTED        true (default) or false
  DEPLOY_REQUESTED       false (default) or true
  ACCESS_MODE            private (default) or public
  CI_RUN_ID              Positive CI run number; falls back to BUILD_NUMBER
  IMAGE_REGISTRY         Must be ccr.ccs.tencentyun.com/tiancia-trade when set
  BUILD_CACHE_DIR        Persistent local cache; defaults to $HOME/.cache/trade-build-cache
  OUTPUT_DIR             Artifact directory; defaults to $PWD/.ci-output
  EXISTING_RELEASE_MANIFEST  Complete non-secret manifest text for build=false
  TCR_USERNAME / TCR_PASSWORD  Injected registry credentials
  DEPLOY_ROOT            Existing production repository; default /opt/trade
  DEPLOY_HOST / DEPLOY_USER / DEPLOY_PORT  SSH destination; port defaults to 22
  DEPLOY_SSH_KEY_FILE / DEPLOY_KNOWN_HOSTS_FILE  CI-injected credential files
  DEPLOY_WAIT_TIMEOUT    Production health timeout in seconds; default 300

Partial builds read the current production image manifest over SSH. Full build-only
runs need no production SSH credentials. Deployment uses the scripts from this checkout.
The previous release.env artifact is removed at the start. A new manifest is published
only after every build/push succeeds, and is retained if subsequent deployment fails.
No deployment .env or runtime credentials are read from the CI checkout.
EOF
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
cleanup() {
  local status=$?
  trap - EXIT
  if [[ $builder_created == true ]]; then
    docker buildx rm --force "$builder_name" >/dev/null 2>&1 || true
  fi
  [[ -z $record_tmp ]] || rm -f -- "$record_tmp" || true
  if [[ -n $work_dir && ${work_dir%/*} == "$work_parent" && ${work_dir##*/} == trade-managed-ci.* \
    && ! -L $work_dir && $(realpath -m -- "$work_dir") == "$work_dir" ]]; then
    rm -rf -- "$work_dir" || true
  fi
  exit "$status"
}
trap cleanup EXIT

if (($#)); then
  [[ $# == 1 && $1 == --help ]] && { usage; exit 0; }
  fail 'This entry point accepts environment parameters only; use --help'
fi

# Clear a previous run's artifact before validation/build failures can be mistaken
# for a fresh successful build. Never remove the output directory or its other files.
[[ ! -L $OUTPUT_DIR ]] || fail 'OUTPUT_DIR must not be a symbolic link'
mkdir -p -- "$OUTPUT_DIR"
OUTPUT_DIR=$(cd -- "$OUTPUT_DIR" && pwd -P)
[[ $OUTPUT_DIR != / ]] || fail 'OUTPUT_DIR must not be the filesystem root'
rm -f -- "$OUTPUT_DIR/release.env"

case "$BUILD_REQUESTED" in true|false) ;; *) fail 'BUILD_REQUESTED must be true or false' ;; esac
case "$DEPLOY_REQUESTED" in true|false) ;; *) fail 'DEPLOY_REQUESTED must be true or false' ;; esac
[[ $BUILD_REQUESTED == true || $DEPLOY_REQUESTED == true ]] || fail 'Select building, deployment, or both'
case "$SERVICE" in
  backend|cockpit|x-console|egress) targets=("$SERVICE") ;;
  frontends) targets=(cockpit x-console) ;;
  all) targets=("${names[@]}") ;;
  *) fail 'Unsupported SERVICE selection' ;;
esac
case "$ACCESS_MODE" in private|public) ;; *) fail 'ACCESS_MODE must be private or public' ;; esac
[[ $CI_RUN_ID =~ ^[1-9][0-9]{0,19}$ ]] || fail 'CI_RUN_ID (or BUILD_NUMBER) must be a positive run number of at most 20 digits'
[[ ${IMAGE_REGISTRY:-$EXPECTED_REGISTRY} == "$EXPECTED_REGISTRY" ]] || fail 'IMAGE_REGISTRY must use the configured TCR namespace'
IMAGE_REGISTRY=$EXPECTED_REGISTRY
DEPLOY_ROOT=${DEPLOY_ROOT%/}
[[ $DEPLOY_ROOT =~ ^/[A-Za-z0-9_./-]+$ && $DEPLOY_ROOT != *'/../'* && $DEPLOY_ROOT != */.. \
  && $DEPLOY_ROOT != *'/./'* && $DEPLOY_ROOT != */. ]] || fail 'DEPLOY_ROOT must be an absolute repository path without dot segments'
[[ $WAIT_TIMEOUT =~ ^[1-9][0-9]*$ ]] || fail 'DEPLOY_WAIT_TIMEOUT must be a positive number of seconds'
[[ -n ${TCR_USERNAME:-} && -n ${TCR_PASSWORD:-} ]] || fail 'Inject TCR_USERNAME and TCR_PASSWORD through CI credentials'
[[ ! $TCR_USERNAME =~ [[:cntrl:]] && $TCR_PASSWORD != *$'\n'* && $TCR_PASSWORD != *$'\r'* ]] \
  || fail 'Registry credentials must not contain line breaks or control characters in the username'

work_parent=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
work_dir=$(mktemp -d "$work_parent/trade-managed-ci.XXXXXX")
nonce=$(od -An -N6 -tx1 /dev/urandom | tr -d '[:space:]')
[[ $nonce =~ ^[a-f0-9]{12}$ ]] || fail 'Could not generate a unique release suffix'

read_manifest() {
  local file=$1 destination=$2 require_version=$3 line key value
  local -n values=$destination
  values=()
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    [[ $line =~ ^[[:space:]]*(#.*)?$ ]] && continue
    [[ $line =~ ^([A-Z_]+)=([A-Za-z0-9][A-Za-z0-9._:/@+-]*)$ ]] || fail 'Invalid image manifest syntax'
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    case "$key" in BACKEND_IMAGE|COCKPIT_IMAGE|X_CONSOLE_IMAGE|EGRESS_IMAGE|RELEASE_VERSION) ;; *) fail 'Unexpected image manifest key' ;; esac
    [[ ! -v values[$key] && $value != *://* ]] || fail 'Duplicate or invalid image manifest value'
    if [[ $key == RELEASE_VERSION ]]; then
      [[ $value =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ && $value != latest ]] || fail 'Invalid immutable RELEASE_VERSION'
    fi
    values[$key]=$value
  done < "$file"
  for key in "${keys[@]}"; do
    [[ -n ${values[$key]:-} ]] || fail 'Incomplete image manifest'
  done
  if [[ $require_version == true ]]; then
    [[ -n ${values[RELEASE_VERSION]:-} ]] || fail 'Image manifest requires RELEASE_VERSION'
  fi
}

validate_selected_images() {
  local destination=$1 index service selected
  local -n values=$destination
  for index in "${!names[@]}"; do
    service=${names[$index]}
    for selected in "${targets[@]}"; do
      [[ $selected == "$service" ]] || continue
      [[ ${values[${keys[$index]}]} == "$IMAGE_REGISTRY/$service:${values[RELEASE_VERSION]}" ]] \
        || fail 'Selected service was not built in this manifest version and TCR namespace'
    done
  done
}

write_manifest() {
  local file=$1 destination=$2 key
  local -n values=$destination
  {
    printf 'RELEASE_VERSION=%s\n' "${values[RELEASE_VERSION]}"
    for key in "${keys[@]}"; do printf '%s=%s\n' "$key" "${values[$key]}"; done
  } > "$file"
}

declare -A manifest=() baseline=()
if [[ $BUILD_REQUESTED == false ]]; then
  [[ -n ${EXISTING_RELEASE_MANIFEST:-} ]] || fail 'Provide EXISTING_RELEASE_MANIFEST for build=false'
  printf '%s\n' "$EXISTING_RELEASE_MANIFEST" > "$work_dir/release.env"
  read_manifest "$work_dir/release.env" manifest true
  validate_selected_images manifest
fi

need_ssh=false
if [[ $DEPLOY_REQUESTED == true || ( $BUILD_REQUESTED == true && $SERVICE != all ) ]]; then
  need_ssh=true
fi
if [[ $need_ssh == true ]]; then
  for tool in ssh scp; do command -v "$tool" >/dev/null || fail "$tool is required"; done
  [[ ${DEPLOY_HOST:-} =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || fail 'Inject a valid DEPLOY_HOST'
  [[ ${DEPLOY_USER:-} =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]] || fail 'Inject a valid DEPLOY_USER'
  [[ $DEPLOY_PORT =~ ^[0-9]{1,5}$ ]] && ((10#$DEPLOY_PORT >= 1 && 10#$DEPLOY_PORT <= 65535)) \
    || fail 'DEPLOY_PORT must be between 1 and 65535'
  for credential in "${DEPLOY_SSH_KEY_FILE:-}" "${DEPLOY_KNOWN_HOSTS_FILE:-}"; do
    [[ -f $credential && -r $credential && -s $credential && $credential != *'"'* && $credential != *'\'* \
      && ! $credential =~ [[:cntrl:]] ]] || fail 'Inject readable SSH key and known-hosts files with safe paths'
  done
  mkdir -- "$work_dir/ssh"
  ssh_config="$work_dir/ssh/config"
  cat > "$ssh_config" <<EOF
Host trade-deploy
  HostName $DEPLOY_HOST
  Port $DEPLOY_PORT
  User $DEPLOY_USER
  IdentityFile "$DEPLOY_SSH_KEY_FILE"
  UserKnownHostsFile "$DEPLOY_KNOWN_HOSTS_FILE"
  BatchMode yes
  IdentitiesOnly yes
  IdentityAgent none
  StrictHostKeyChecking yes
  ConnectTimeout 15
  ServerAliveInterval 15
  ServerAliveCountMax 4
EOF
fi

if [[ $DEPLOY_REQUESTED == true ]]; then
  ssh -F "$ssh_config" trade-deploy "
    set -eu
    test -w '$DEPLOY_ROOT/deploy'
    test -f '$DEPLOY_ROOT/compose.prod.yaml'
    command -v flock >/dev/null
    docker info >/dev/null
    docker compose version >/dev/null
  "
fi

if [[ $BUILD_REQUESTED == true ]]; then
  command -v git >/dev/null || fail 'git is required for source-pinned builds'
  command -v docker >/dev/null || fail 'docker is required for builds'
  source_sha=$(git -C "$SCRIPT_ROOT" rev-parse HEAD)
  [[ $source_sha =~ ^[a-f0-9]{40}$ ]] || fail 'The checkout must resolve to one full 40-character commit SHA'
  image_tag="sha-$source_sha-cn-$CI_RUN_ID-$nonce"
  if [[ -z $BUILD_CACHE_DIR ]]; then
    [[ -n ${HOME:-} ]] || fail 'Set HOME or BUILD_CACHE_DIR for a persistent local build cache'
    BUILD_CACHE_DIR="$HOME/.cache/trade-build-cache"
  fi
  build_args=(--registry "$IMAGE_REGISTRY" --tag "$image_tag" --platform linux/amd64
    --output "$work_dir/release.env" --cache-dir "$BUILD_CACHE_DIR" --push)
  if [[ $SERVICE != all ]]; then
    scp -F "$ssh_config" "trade-deploy:$DEPLOY_ROOT/deploy/releases/current.env" "$work_dir/base-release.env"
    read_manifest "$work_dir/base-release.env" baseline false
    build_args+=(--base "$work_dir/base-release.env")
  fi
  mkdir -- "$work_dir/docker"
  export DOCKER_CONFIG="$work_dir/docker"
  printf '%s\n' "$TCR_PASSWORD" | docker login "$REGISTRY_HOST" --username "$TCR_USERNAME" --password-stdin
  builder_name="trade-ci-$CI_RUN_ID-$nonce"
  docker buildx create --name "$builder_name" --driver docker-container --use
  builder_created=true
  export BUILDX_BUILDER="$builder_name"
  printf 'Managed CI building services: %s; version: %s\n' "${targets[*]}" "$image_tag"
  bash "$SCRIPT_ROOT/deploy/scripts/build-images.sh" "${build_args[@]}" "${targets[@]}"
  read_manifest "$work_dir/release.env" manifest true
  [[ ${manifest[RELEASE_VERSION]} == "$image_tag" ]] || fail 'Build output does not match this source-pinned release'
fi
validate_selected_images manifest
write_manifest "$work_dir/release.env" manifest
record_tmp=$(mktemp "$OUTPUT_DIR/.release.XXXXXX")
cat -- "$work_dir/release.env" > "$record_tmp"
mv -f -- "$record_tmp" "$OUTPUT_DIR/release.env"
record_tmp=''
printf 'Image manifest ready: %s/release.env\n' "$OUTPUT_DIR"

if [[ $DEPLOY_REQUESTED == true ]]; then
  remote_dir="$DEPLOY_ROOT/deploy/incoming/${manifest[RELEASE_VERSION]}-cn-$CI_RUN_ID-$nonce"
  ssh -F "$ssh_config" trade-deploy "set -eu; umask 077; mkdir -p '$DEPLOY_ROOT/deploy/incoming'; mkdir '$remote_dir'"
  scp -F "$ssh_config" "$work_dir/release.env" "$SCRIPT_ROOT/deploy/scripts/release.sh" \
    "$SCRIPT_ROOT/deploy/scripts/cleanup-images.sh" "trade-deploy:$remote_dir/"
  remote_script=$(cat <<'REMOTE'
set +x
set -euo pipefail
root=$1
manifest=$2
access=$3
service=$4
registry_host=$5
registry=$6
wait_timeout=$7
case "$service" in
  backend|cockpit|x-console|egress) targets=("$service") ;;
  frontends) targets=(cockpit x-console) ;;
  all) targets=() ;;
  *) exit 1 ;;
esac
cd "$root"
umask 077
docker_config=$(mktemp -d)
trap 'rm -f -- "$docker_config/config.json"; rmdir -- "$docker_config" 2>/dev/null || true' EXIT
export DOCKER_CONFIG="$docker_config"
IFS= read -r registry_user
[[ -n $registry_user ]] || { printf 'Missing registry username on stdin.\n' >&2; exit 1; }
docker login "$registry_host" --username "$registry_user" --password-stdin
release_script="$(dirname -- "$manifest")/release.sh"
bash "$release_script" validate --root "$root" --release "$manifest" --access "$access"
DEPLOY_WAIT_TIMEOUT="$wait_timeout" bash "$release_script" deploy "${targets[@]}" \
  --root "$root" --release "$manifest" --access "$access"
printf 'DEPLOYMENT_OK\n'
if ! bash "$(dirname -- "$manifest")/cleanup-images.sh" --root "$root" --registry "$registry" --apply; then
  printf 'Warning: deployment succeeded, but old-image cleanup was skipped or incomplete.\n' >&2
fi
REMOTE
  )
  printf -v remote_command 'bash -c %q -- %q %q %q %q %q %q %q' \
    "$remote_script" "$DEPLOY_ROOT" "$remote_dir/release.env" "$ACCESS_MODE" "$SERVICE" \
    "$REGISTRY_HOST" "$IMAGE_REGISTRY" "$WAIT_TIMEOUT"
  printf '%s\n%s\n' "$TCR_USERNAME" "$TCR_PASSWORD" | ssh -F "$ssh_config" trade-deploy "$remote_command"
fi
