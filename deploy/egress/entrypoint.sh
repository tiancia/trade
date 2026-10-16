#!/bin/sh
set -eu
umask 077

: "${EGRESS_SSH_HOST:?Set EGRESS_SSH_HOST to the German VPS address}"
EGRESS_SSH_PORT=${EGRESS_SSH_PORT:-22}
EGRESS_SSH_USER=${EGRESS_SSH_USER:-trade-proxy}
EGRESS_REMOTE_PORT=${EGRESS_REMOTE_PORT:-3128}

case "$EGRESS_SSH_HOST" in
    -*|*[!a-zA-Z0-9.:-]*) echo "Invalid EGRESS_SSH_HOST" >&2; exit 1 ;;
esac
case "$EGRESS_SSH_USER" in
    ''|-*|*[!a-zA-Z0-9_.-]*) echo "Invalid EGRESS_SSH_USER" >&2; exit 1 ;;
esac
for port in "$EGRESS_SSH_PORT" "$EGRESS_REMOTE_PORT"; do
    case "$port" in
        ''|*[!0-9]*) echo "SSH and remote proxy ports must be integers" >&2; exit 1 ;;
    esac
    if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
        echo "SSH and remote proxy ports must be between 1 and 65535" >&2
        exit 1
    fi
done

if [ ! -r /run/secrets/egress_key ] || [ ! -r /run/secrets/egress_known_hosts ]; then
    echo "Mount egress_key and egress_known_hosts readable by container UID 10001" >&2
    exit 1
fi

# Compose mounts the source files read-only. Copy to /tmp (a runtime tmpfs) so
# OpenSSH can enforce private-key permissions without changing the host files.
ssh_dir=$(mktemp -d /tmp/trade-egress.XXXXXX)
cp /run/secrets/egress_key "$ssh_dir/key"
cp /run/secrets/egress_known_hosts "$ssh_dir/known_hosts"
chmod 600 "$ssh_dir/key" "$ssh_dir/known_hosts"

exec ssh -F /dev/null -NT -g \
    -i "$ssh_dir/key" \
    -p "$EGRESS_SSH_PORT" \
    -L "0.0.0.0:13128:127.0.0.1:$EGRESS_REMOTE_PORT" \
    -o BatchMode=yes \
    -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=yes \
    -o "UserKnownHostsFile=$ssh_dir/known_hosts" \
    -o GlobalKnownHostsFile=/dev/null \
    -o UpdateHostKeys=no \
    -o ExitOnForwardFailure=yes \
    -o ConnectTimeout=15 \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=3 \
    -o LogLevel=ERROR \
    "$EGRESS_SSH_USER@$EGRESS_SSH_HOST"
