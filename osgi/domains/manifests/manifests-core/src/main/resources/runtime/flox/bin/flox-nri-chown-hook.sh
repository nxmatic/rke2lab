#!/usr/bin/env -S bash -uxo pipefail
# OCI prestart hook: fix ownership of $HOME and $HOME/.flox in the container rootfs.
#
# Runs in the runtime (host) namespace, NOT inside the container. Container
# files are reachable via ${bundle}/rootfs as delivered in the OCI state JSON
# on stdin. Failures are non-fatal — chown is best-effort and the .flox bind
# mount is read-only anyway.
#
# Usage: flox-nri-chown-hook.sh <uid> <gid> <home-dir>
#
# Logging: stdout/stderr are piped to systemd-journald via logger(1). View with:
#   journalctl -ft flox-nri-chown-hook
# stderr (chown errors, xtrace output) is recorded at daemon.err priority.

TAG=flox-nri-chown-hook
exec > >(logger --id=$$ -t "$TAG" -p daemon.info)
exec 2> >(logger --id=$$ -t "$TAG" -p daemon.err)

state="$(cat)"
bundle="$(printf '%s' "$state" | sed -n 's/.*"bundle"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"

if [ -z "$bundle" ]; then
    echo "ERROR: could not parse bundle path from OCI state" >&2
    exit 0
fi

rootfs="${bundle%/}/rootfs"

uid="${1:-0}"
gid="${2:-0}"
home="${3:-/root}"

target_home="${rootfs}${home}"
target_flox="${target_home}/.flox"

chown "${uid}:${gid}" "${target_home}" "${target_flox}" || true

exit 0
