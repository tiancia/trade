#!/bin/sh
set -eu
# Never run a notification service with a missing or empty receiver.
target=$(cat /run/secrets/alertmanager_webhook_url)
case "$target" in
  https://?*) ;;
  *) echo 'Configure an external HTTPS Alertmanager-compatible webhook secret' >&2; exit 1 ;;
esac
case "$target" in
  *example.com*|*example.org*|*.invalid*|*localhost*|*127.0.0.1*|*[[:space:]]*)
    echo 'Replace the placeholder webhook with a real external receiver' >&2; exit 1 ;;
esac
unset target
/bin/amtool check-config /etc/alertmanager/alertmanager.yml
exec /bin/alertmanager --config.file=/etc/alertmanager/alertmanager.yml --storage.path=/alertmanager --cluster.listen-address= "$@"
