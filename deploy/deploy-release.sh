#!/usr/bin/env bash
set -Eeuo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
[[ ${EUID} -eq 0 && $# -eq 1 && "$1" =~ ^[0-9a-f]{40}$ ]] || exit 64
# systemd owns the job: losing the SSH connection does not interrupt a database backup or restart.
result=0
systemd-run --quiet --wait --collect --unit=dataforge-one-click \
  --property=Type=oneshot --property=TimeoutStartSec=30min \
  /usr/local/libexec/dataforge-deploy-worker "$1" || result=$?
journalctl --no-pager -u dataforge-one-click.service -n 120 || true
exit "$result"
