#!/usr/bin/env -S bash -exu -o pipefail

: "Load RKE2 environment" # @codebase
source <(flox activate --dir /var/lib/rancher/rke2)

MOUNT_UNIT_PATH=/etc/systemd/system/var-lib-rancher-rke2-agent-containerd-io.containerd.snapshotter.v1.zfs.mount
MOUNT_UNIT_DROPIN_DIR="${MOUNT_UNIT_PATH}.d"

: "Generate node-specific mount unit for containerd ZFS snapshotter"
cat > "${MOUNT_UNIT_PATH}" <<EOF
[Unit]
Description=Mount containerd zfs snapshotter directory for RKE2 (ZFS dataset)
Documentation=https://github.com/nxmatic/rke2lab
DefaultDependencies=no
Before=rke2-${RKE2LAB_NODE_KIND}.service

[Mount]
What=tank/rke2/control-nodes/${RKE2LAB_NODE_NAME}/containerd
Where=/var/lib/rancher/rke2/agent/containerd/io.containerd.snapshotter.v1.zfs
Type=zfs
Options=defaults

[Install]
RequiredBy=rke2-${RKE2LAB_NODE_KIND}.service
EOF

: "Remove obsolete drop-in override if present"
if [[ -d "${MOUNT_UNIT_DROPIN_DIR}" ]]; then
	rm -rf "${MOUNT_UNIT_DROPIN_DIR}"
fi

: "Reload systemd to recognize drop-in configuration"
systemctl daemon-reload
