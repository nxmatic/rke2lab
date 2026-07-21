#!/bin/sh
set -eux

# Entry point for running NRI plugin as main container in DaemonSet
# Pod runs with host /nix mounted, so we can access the Nix store directly
# The init container has already built and GC-rooted the plugin

echo "=== NRI Plugin Runner ==="
echo "Checking for GC root..."

# Find the plugin binary via the GC root created by installer
GC_ROOT_PATH="/nix/var/nix/gcroots/flox-runtime/flox-nri-plugin"

if [ ! -L "${GC_ROOT_PATH}" ]; then
    echo "ERROR: NRI plugin GC root not found: ${GC_ROOT_PATH}" >&2
    echo "Listing /nix/var/nix/gcroots/flox-runtime/:" >&2
    ls -la /nix/var/nix/gcroots/flox-runtime/ || echo "Directory does not exist" >&2
    exit 1
fi

# Resolve the symlink to get the actual Nix store path
echo "Resolving symlink ${GC_ROOT_PATH}..."
PLUGIN_PKG_PATH="$(readlink "${GC_ROOT_PATH}")"
echo "Symlink points to: ${PLUGIN_PKG_PATH}"

# If relative, make it absolute
case "${PLUGIN_PKG_PATH}" in
/*)
    # Already absolute
    ;;
*)
    # Relative, make absolute
    PLUGIN_PKG_PATH="$(cd "$(dirname "${GC_ROOT_PATH}")" && cd "${PLUGIN_PKG_PATH}" && pwd)"
    ;;
esac

echo "Resolved to: ${PLUGIN_PKG_PATH}"
PLUGIN_BIN="${PLUGIN_PKG_PATH}/bin/flox-nri-plugin"

if [ ! -x "${PLUGIN_BIN}" ]; then
    echo "ERROR: NRI plugin binary not found or not executable: ${PLUGIN_BIN}" >&2
    echo "Listing ${PLUGIN_PKG_PATH}/bin/:" >&2
    ls -la "${PLUGIN_PKG_PATH}/bin/" || echo "Directory does not exist" >&2
    exit 1
fi

echo "Found NRI plugin binary: ${PLUGIN_BIN}"
echo "Starting NRI plugin..."
exec "${PLUGIN_BIN}"
