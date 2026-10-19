#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SERVER=${TRADE_DEPLOY_SERVER:-}
SERVER_FILE="$ROOT_DIR/deploy/upload-server.local"
PORT=''
IDENTITY=''
REMOTE_ROOT=/opt/trade
OUTPUT_ROOT="$(dirname -- "$ROOT_DIR")/trade-deployments"
VERSION="$(date -u +%Y%m%d-%H%M%S)-$$"
PLATFORM=${BUILD_PLATFORM:-linux/amd64}
REGISTRY=${IMAGE_REGISTRY:-trade}
TARGETS=()

usage() {
  cat <<'EOF'
Build selected images, export them, upload, and verify their SHA256 checksums.

Usage: bash deploy/scripts/build-upload.sh [options] SERVICE [SERVICE ...]

Services: backend cockpit x-console egress, or all for all four images.

  --server TARGET      SSH target (user@host or SSH alias); defaults to
                       TRADE_DEPLOY_SERVER or deploy/upload-server.local
  --port PORT          Override the port from your SSH configuration
  --identity FILE      Override the SSH identity file
  --tag VERSION        Override the generated UTC timestamp/process release tag
  --platform PLATFORM  linux/amd64 (default), or linux/arm64
  --remote-root DIR    Existing server repository; defaults to /opt/trade
  --output-dir DIR     Parent for local version directories;
                       defaults to ../trade-deployments beside this repository
  --help               Show this help

The local server file contains one SSH target, not shell commands or passwords.
SSH host-key verification stays enabled. Existing SSH config and agents apply.
Partial builds download deploy/releases/current.env from the target server.
Only the chosen images and the complete release manifest are uploaded to a new
deploy/incoming/VERSION directory. Containers and active configuration are not
changed. The script prints the commands for a separate server-side release.
EOF
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
require_value() { [[ $# -ge 2 && -n $2 && $2 != --* ]] || fail "$1 requires a value"; }

while (($#)); do
  case "$1" in
    --server) require_value "$@"; SERVER=$2; shift 2 ;;
    --port) require_value "$@"; PORT=$2; shift 2 ;;
    --identity) require_value "$@"; IDENTITY=$2; shift 2 ;;
    --tag) require_value "$@"; VERSION=$2; shift 2 ;;
    --platform) require_value "$@"; PLATFORM=$2; shift 2 ;;
    --remote-root) require_value "$@"; REMOTE_ROOT=$2; shift 2 ;;
    --output-dir) require_value "$@"; OUTPUT_ROOT=$2; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    backend|cockpit|x-console|egress|all) TARGETS+=("$1"); shift ;;
    *) fail "Unknown argument: $1" ;;
  esac
done

((${#TARGETS[@]} > 0)) || { usage >&2; fail 'Choose at least one service, or all'; }
if [[ ${TARGETS[0]} == all && ${#TARGETS[@]} == 1 ]]; then
  TARGETS=(backend cockpit x-console egress)
fi
declare -A selected=()
for service in "${TARGETS[@]}"; do
  [[ $service != all ]] || fail 'Use all by itself'
  [[ ! -v selected[$service] ]] || fail "Duplicate service: $service"
  selected[$service]=true
done

if [[ -z $SERVER && -f $SERVER_FILE ]]; then
  SERVER=$(cat -- "$SERVER_FILE")
  SERVER=${SERVER%$'\r'}
fi
[[ $SERVER =~ ^([A-Za-z0-9_][A-Za-z0-9_.-]*@)?[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || fail 'Set --server user@host (or an SSH alias), or save it in deploy/upload-server.local'
[[ $VERSION =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ && $VERSION != latest ]] || fail 'Use a valid explicit release tag, not latest'
[[ $PLATFORM =~ ^linux/(amd64|arm64)(/v[0-9]+)?$ ]] || fail 'Supported target platforms: linux/amd64 or linux/arm64'
REGISTRY=${REGISTRY%/}
[[ $REGISTRY =~ ^[a-z0-9][a-z0-9._:/-]*[a-z0-9]$ && $REGISTRY != *://* ]] || fail 'IMAGE_REGISTRY must be a Docker image namespace without a URL scheme'
REMOTE_ROOT=${REMOTE_ROOT%/}
[[ $REMOTE_ROOT =~ ^/[A-Za-z0-9_./-]+$ && $REMOTE_ROOT != *'/../'* && $REMOTE_ROOT != */.. && $REMOTE_ROOT != *'/./'* && $REMOTE_ROOT != */. ]] || fail 'Use an absolute remote repository path without spaces or dot segments'
if [[ -n $PORT ]]; then
  [[ $PORT =~ ^[0-9]{1,5}$ ]] && ((10#$PORT >= 1 && 10#$PORT <= 65535)) || fail 'SSH port must be between 1 and 65535'
fi
[[ -z $IDENTITY || -f $IDENTITY ]] || fail 'SSH identity file does not exist'
[[ -n $OUTPUT_ROOT ]] || fail 'Output directory cannot be empty'
# Git Bash may have a different HOME and SSH agent from Windows OpenSSH.
# Prefer the native pair there so an existing Windows SSH login keeps working.
SSH_COMMAND=${TRADE_SSH_COMMAND:-ssh}
SCP_COMMAND=${TRADE_SCP_COMMAND:-scp}
if [[ ${OSTYPE:-} == msys* && -n ${SYSTEMROOT:-} ]]; then
  windows_ssh_dir="$(cygpath -u "$SYSTEMROOT")/System32/OpenSSH"
  if [[ -z ${TRADE_SSH_COMMAND:-} && -x $windows_ssh_dir/ssh.exe ]]; then
    SSH_COMMAND="$windows_ssh_dir/ssh.exe"
  fi
  if [[ -z ${TRADE_SCP_COMMAND:-} && -x $windows_ssh_dir/scp.exe ]]; then
    SCP_COMMAND="$windows_ssh_dir/scp.exe"
  fi
fi
for command in docker gzip sha256sum "$SSH_COMMAND" "$SCP_COMMAND"; do
  command -v "$command" >/dev/null || fail "$command is required"
done
[[ $(docker info --format '{{.OSType}}') == linux ]] || fail 'Start Docker with a Linux Engine before building'

ssh_args=(-o StrictHostKeyChecking=yes -o ConnectTimeout=15)
scp_args=(-o StrictHostKeyChecking=yes -o ConnectTimeout=15)
if [[ -n $PORT ]]; then
  ssh_args+=(-p "$PORT")
  scp_args+=(-P "$PORT")
fi
if [[ -n $IDENTITY ]]; then
  ssh_args+=(-i "$IDENTITY")
  scp_args+=(-i "$IDENTITY")
fi

ARTIFACT_DIR="$OUTPUT_ROOT/$VERSION"
REMOTE_DIR="$REMOTE_ROOT/deploy/incoming/$VERSION"
printf 'Checking SSH and upload destination: %s:%s\n' "$SERVER" "$REMOTE_DIR"
"$SSH_COMMAND" "${ssh_args[@]}" "$SERVER" "test -d '$REMOTE_ROOT/deploy' && test -w '$REMOTE_ROOT/deploy' && command -v sha256sum >/dev/null && test ! -e '$REMOTE_DIR'"
mkdir -p -- "$OUTPUT_ROOT"
mkdir -- "$ARTIFACT_DIR"
# Use absolute local paths for scp even when --output-dir was relative.
ARTIFACT_DIR=$(cd -- "$ARTIFACT_DIR" && pwd)

build_args=(--registry "$REGISTRY" --tag "$VERSION" --platform "$PLATFORM" --output "$ARTIFACT_DIR/release.env")
if ((${#TARGETS[@]} < 4)); then
  printf 'Downloading the server current image manifest.\n'
  "$SCP_COMMAND" "${scp_args[@]}" "$SERVER:$REMOTE_ROOT/deploy/releases/current.env" "$ARTIFACT_DIR/base-release.env"
  build_args+=(--base "$ARTIFACT_DIR/base-release.env")
fi
bash "$ROOT_DIR/deploy/scripts/build-images.sh" "${build_args[@]}" "${TARGETS[@]}"

images=()
for service in "${TARGETS[@]}"; do
  images+=("$REGISTRY/$service:$VERSION")
done
printf 'Exporting selected images to %s/images.tar.gz\n' "$ARTIFACT_DIR"
docker save "${images[@]}" | gzip -1 > "$ARTIFACT_DIR/images.tar.gz.partial"
mv -- "$ARTIFACT_DIR/images.tar.gz.partial" "$ARTIFACT_DIR/images.tar.gz"
(
  cd -- "$ARTIFACT_DIR"
  sha256sum images.tar.gz release.env > SHA256SUMS
)

# A new staging directory keeps uploads separate from active release records.
"$SSH_COMMAND" "${ssh_args[@]}" "$SERVER" "mkdir -p '$REMOTE_ROOT/deploy/incoming' && mkdir '$REMOTE_DIR'"
"$SCP_COMMAND" "${scp_args[@]}" "$ARTIFACT_DIR/images.tar.gz" "$ARTIFACT_DIR/release.env" "$ARTIFACT_DIR/SHA256SUMS" "$SERVER:$REMOTE_DIR/"
"$SSH_COMMAND" "${ssh_args[@]}" "$SERVER" "cd '$REMOTE_DIR' && sha256sum -c SHA256SUMS"

printf '\nUpload and checksum verification succeeded.\nLocal files: %s\nServer files: %s:%s\n' "$ARTIFACT_DIR" "$SERVER" "$REMOTE_DIR"
printf '\nRun these commands separately in the server Bash to load and deploy:\n'
printf "cd '%s'\n" "$REMOTE_ROOT"
printf "docker load -i '%s/images.tar.gz'\n" "$REMOTE_DIR"
printf "bash deploy/scripts/release.sh validate --release '%s/release.env'\n" "$REMOTE_DIR"
printf 'DEPLOY_WAIT_TIMEOUT=300 bash deploy/scripts/release.sh deploy'
if ((${#TARGETS[@]} < 4)); then
  printf ' %s' "${TARGETS[@]}"
fi
printf " --release '%s/release.env' --no-pull\n" "$REMOTE_DIR"
printf '\nFor public HTTPS, add --access public to both release.sh commands.\n'
printf 'If Compose, Nginx or deployment scripts changed, sync those files before deploying.\n'
