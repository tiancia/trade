#!/usr/bin/env bash
# Run only after the control pipeline checks out a trusted GitHub main revision.
set +x
set -euo pipefail
umask 077

SOURCE_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
export OUTPUT_DIR="$SOURCE_ROOT/.ci-output"
credential_dir=''
credential_parent=''
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n $credential_dir && ${credential_dir%/*} == "$credential_parent" \
    && ${credential_dir##*/} == trade-cnb.* && ! -L $credential_dir \
    && $(realpath -m -- "$credential_dir") == "$credential_dir" ]]; then
    rm -rf -- "$credential_dir" || true
  fi
  exit "$status"
}
trap cleanup EXIT

# No stale manifest may be archived even when source or parameter checks fail.
[[ ! -L $OUTPUT_DIR ]] || fail 'The CI output directory must not be a symbolic link'
mkdir -p -- "$OUTPUT_DIR"
rm -f -- "$OUTPUT_DIR/release.env" "$OUTPUT_DIR"/release-*.env
(($# == 0)) || fail 'This adapter accepts environment parameters only'
[[ ${SOURCE_SHA:-} =~ ^[a-f0-9]{40}$ ]] || fail 'SOURCE_SHA must be a full lowercase GitHub commit SHA'
actual_sha=$(git -C "$SOURCE_ROOT" rev-parse HEAD)
[[ $actual_sha == "$SOURCE_SHA" ]] || fail 'SOURCE_SHA does not match the checked-out source'

export SERVICE=${SERVICE:-all}
export BUILD_REQUESTED=${BUILD_REQUESTED:-true}
export DEPLOY_REQUESTED=${DEPLOY_REQUESTED:-false}
export CI_ACTION=${CI_ACTION:-release}
case "$CI_ACTION" in
  release) ;;
  rollback) export BUILD_REQUESTED=false DEPLOY_REQUESTED=true ;;
  *) fail 'CI_ACTION must be release or rollback' ;;
esac
export CI_RUN_ID=${CI_RUN_ID:-$(date +%s)}
export BUILD_CACHE_DIR=${BUILD_CACHE_DIR:-/root/.cache/trade-build-cache}
# The hosted service owns BuildKit; nested docker-container builders cannot run.
export CI_BUILDER_MODE=platform
case "$SERVICE" in all|backend|cockpit|x-console|frontends|egress) ;; *) fail 'Unsupported SERVICE selection' ;; esac
case "$BUILD_REQUESTED" in true|false) ;; *) fail 'BUILD_REQUESTED must be true or false' ;; esac
case "$DEPLOY_REQUESTED" in true|false) ;; *) fail 'DEPLOY_REQUESTED must be true or false' ;; esac
[[ $BUILD_REQUESTED == true || $DEPLOY_REQUESTED == true ]] || fail 'Select building, deployment, or both'
[[ $CI_RUN_ID =~ ^[1-9][0-9]{0,19}$ ]] || fail 'CI_RUN_ID must be a positive run number of at most 20 digits'

if [[ $DEPLOY_REQUESTED == true || ( $BUILD_REQUESTED == true && $SERVICE != all ) ]]; then
  [[ -n ${DEPLOY_SSH_PRIVATE_KEY:-} && -n ${DEPLOY_KNOWN_HOSTS:-} ]] \
    || fail 'The selected operation requires SSH private-key and verified known-hosts configuration'
  credential_parent=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
  case "$credential_parent/" in "$SOURCE_ROOT/"*) fail 'Credential files must be outside the source checkout' ;; esac
  credential_dir=$(mktemp -d "$credential_parent/trade-cnb.XXXXXX")
  export DEPLOY_SSH_KEY_FILE="$credential_dir/key"
  export DEPLOY_KNOWN_HOSTS_FILE="$credential_dir/known_hosts"
  printf '%s\n' "$DEPLOY_SSH_PRIVATE_KEY" | tr -d '\r' > "$DEPLOY_SSH_KEY_FILE"
  printf '%s\n' "$DEPLOY_KNOWN_HOSTS" | tr -d '\r' > "$DEPLOY_KNOWN_HOSTS_FILE"
  chmod 600 -- "$DEPLOY_SSH_KEY_FILE" "$DEPLOY_KNOWN_HOSTS_FILE"
fi
unset DEPLOY_SSH_PRIVATE_KEY DEPLOY_KNOWN_HOSTS

status=0
bash "$SOURCE_ROOT/deploy/scripts/managed-ci.sh" || status=$?
[[ $CI_ACTION != rollback ]] || exit "$status"

valid_manifest() {
  local line key value
  declare -A values=()
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    [[ $line =~ ^([A-Z_]+)=([A-Za-z0-9][A-Za-z0-9._:/@+-]*)$ ]] || return 1
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    case "$key" in RELEASE_VERSION|BACKEND_IMAGE|COCKPIT_IMAGE|X_CONSOLE_IMAGE|EGRESS_IMAGE) ;; *) return 1 ;; esac
    [[ ! -v values[$key] && $value != *://* ]] || return 1
    values[$key]=$value
  done < "$OUTPUT_DIR/release.env"
  for key in RELEASE_VERSION BACKEND_IMAGE COCKPIT_IMAGE X_CONSOLE_IMAGE EGRESS_IMAGE; do
    [[ -n ${values[$key]:-} ]] || return 1
  done
  [[ ${values[RELEASE_VERSION]} =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ \
    && ${values[RELEASE_VERSION]} != latest ]] || return 1
  if [[ $BUILD_REQUESTED == true ]]; then
    [[ ${values[RELEASE_VERSION]} =~ ^sha-${SOURCE_SHA}-cn-${CI_RUN_ID}-[a-f0-9]{12}$ ]] || return 1
  fi
  manifest_version=${values[RELEASE_VERSION]}
}

if [[ -f $OUTPUT_DIR/release.env && ! -L $OUTPUT_DIR/release.env ]] && valid_manifest; then
  # Images are published; this manifest does not assert production deployment success.
  printf '\nIMAGE_MANIFEST_BEGIN\n'
  cat -- "$OUTPUT_DIR/release.env"
  printf '\nIMAGE_MANIFEST_END\n'
  if ! cp -- "$OUTPUT_DIR/release.env" "$OUTPUT_DIR/release-$manifest_version.env"; then
    printf 'Error: could not prepare the published image manifest attachment.\n' >&2
    ((status != 0)) || status=1
  fi
elif ((status == 0)); then
  printf 'Error: managed CI completed without a valid image manifest.\n' >&2
  status=1
else
  printf 'No valid published image manifest was produced.\n' >&2
fi
exit "$status"
