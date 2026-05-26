#!/usr/bin/env bash
# Flox Runtime Shared Library
#
# Provides bootstrap and reconcile modes for the flox-runtime DaemonSet.

set -euo pipefail

# Bootstrap mode: install baseline NRI plugin, hooks, and prepare host environment
flox_runtime::bootstrap() {
	echo "[flox-runtime] Starting bootstrap mode..."

	# Existing installer logic will be refactored here in next step
	# For now, call the existing installer script
	exec "${SCRIPT_MOUNT_DIR}/bin/flox-k8s-runtime-installer.sh"
}

# Reconcile mode: watch dynamic ConfigMap and hot-reload plugin on changes
flox_runtime::reconcile() {
	local marker_file="${DAEMONLESS_HOST_SCRIPT_ROOT}/runtime/flox/.bootstrap-complete"

	echo "[flox-runtime] Starting reconcile mode..."

	# Verify bootstrap completed
	if [[ ! -f "${marker_file}" ]]; then
		echo "ERROR: Bootstrap incomplete, marker missing: ${marker_file}" >&2
		echo "       The init container must complete successfully before reconcile can start." >&2
		exit 1
	fi

	echo "[flox-runtime] Bootstrap verified, entering watch loop..."

	# Source daemonless library for asset materialization
	# shellcheck source=/dev/null
	source "${DAEMONLESS_POLICY_SCRIPT_MOUNT_DIR}/.sh.d/daemonless-host-asset-reconciler.sh"

	# Watch and reconcile the dynamic plugin ConfigMap
	daemonless::host_asset:watch_and_reconcile \
		"${DYNAMIC_PLUGIN_MOUNT_DIR}" \
		"nri-plugin.tar.b64" \
		"nri-plugin.manifest.json" \
		"${DAEMONLESS_HOST_SCRIPT_ROOT}/runtime/flox" \
		"flox-nri-plugin-reload.sh"
}
