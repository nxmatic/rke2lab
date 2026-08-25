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
# Logging: to the hook's own stdout/stderr, captured by the OCI runtime — no
# logger(1)/journald reroute (keeps the hooks uniform + avoids the process-
# substitution/`/dev/log` fragility that breaks the CreateContainer hooks).

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
