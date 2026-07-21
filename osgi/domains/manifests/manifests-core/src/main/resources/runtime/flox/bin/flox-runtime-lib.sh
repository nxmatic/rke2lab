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
    exec "${SCRIPT_MOUNT_DIR}/bin/flox-nri-plugin-installer.sh"
}

# Reconcile mode: watch dynamic ConfigMap and hot-reload plugin on changes
flox_runtime::reconcile() {
    echo "[flox-runtime] Starting reconcile mode..."
    echo "[flox-runtime] Init container completed, entering watch loop..."

    # Source daemonset library for asset materialization
    # shellcheck source=/dev/null
    source "${DAEMONSET_POLICY_SCRIPT_MOUNT_DIR}/.sh.d/daemonset-host-asset-reconciler.sh"

    # Watch and reconcile the dynamic plugin ConfigMap
    daemonset::host_asset:watch_and_reconcile \
        "${DYNAMIC_PLUGIN_MOUNT_DIR}" \
        "nri-plugin.tar.b64" \
        "nri-plugin.manifest.json" \
        "${DAEMONSET_HOST_SCRIPT_ROOT}" \
        "flox-nri-plugin-reload.sh"
}
