#!/usr/bin/env bash
set -euo pipefail

# Flox NRI plugin hot-reload hook
#
# Invoked by daemonset::host_asset:watch_and_reconcile after the dynamic NRI plugin
# archive has been materialized onto the host. This script runs in host namespace.
#
# Responsibilities:
# 1. Find the freshly-materialized NRI plugin binary (from plugin.d.dyn/ or similar)
# 2. Install it to the runtime location where containerd's NRI client will load it
# 3. Signal the running plugin process to terminate gracefully
# 4. Containerd's NRI client automatically reconnects to the restarted plugin
#
# Canonical environment:
# - DAEMONSET_HOST_SCRIPT_ROOT=/srv/host/k8s-daemonset.d/runtime/flox
# - FLOX_RUNTIME_ROOT (falls back to DAEMONSET_HOST_SCRIPT_ROOT)

FLOX_RUNTIME_ROOT="${FLOX_RUNTIME_ROOT:-${DAEMONSET_HOST_SCRIPT_ROOT}}"
FLOX_NRI_PLUGIN_DYN_DIR="${FLOX_RUNTIME_ROOT}/nri-plugin.dyn"
FLOX_NRI_PLUGIN_BINARY="${FLOX_NRI_PLUGIN_DYN_DIR}/bin/flox-nri-plugin"

[[ -x "${FLOX_NRI_PLUGIN_BINARY}" ]] || {
	echo "flox-nri-plugin-reload: dynamic plugin binary not found or not executable: ${FLOX_NRI_PLUGIN_BINARY}" >&2
	exit 1
}

echo "flox-nri-plugin-reload: dynamic plugin binary found at ${FLOX_NRI_PLUGIN_BINARY}"

# The NRI plugin binary is GC-rooted by the initial installer at a Nix store path;
# containerd's NRI client loads plugins from /opt/nri/plugins. The dynamic update
# overwrites the symlink in nri-plugin.dyn/ to point to the new store derivation.
#
# Install (or overwrite) the dynamic binary to the runtime's canonical plugin location.
# The existing NRI plugin installation from the bootstrap ConfigMap lives at a different
# path; the dynamic path takes precedence once present.

NRI_PLUGIN_RUNTIME_BIN="/usr/local/sbin/flox-nri-plugin"

install -D -m 0755 "${FLOX_NRI_PLUGIN_BINARY}" "${NRI_PLUGIN_RUNTIME_BIN}"
echo "flox-nri-plugin-reload: installed dynamic plugin to ${NRI_PLUGIN_RUNTIME_BIN}"

# Signal the running plugin to terminate. Containerd's NRI runtime is designed to
# tolerate plugin disconnects and will reconnect when the plugin restarts. The
# DaemonSet's main container restartPolicy ensures the plugin process is restarted
# automatically by Kubernetes.

if pgrep -f "flox-nri-plugin" >/dev/null; then
	echo "flox-nri-plugin-reload: sending SIGTERM to running flox-nri-plugin process(es)..."
	pkill -TERM -f "flox-nri-plugin" || true
	echo "flox-nri-plugin-reload: signal sent; plugin will restart via DaemonSet restartPolicy"
else
	echo "flox-nri-plugin-reload: no running flox-nri-plugin process found; plugin will start on next container create"
fi

echo "flox-nri-plugin-reload: reload complete"
