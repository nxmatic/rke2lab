#!/usr/bin/env -S bash -exu -o pipefail

source <( flox activate --dir /var/lib/rancher/rke2 )

: "Create drop-in directory for containerd ZFS mount unit"
mkdir -p /etc/systemd/system/var-lib-rancher-rke2-agent-containerd-io.containerd.snapshotter.v1.zfs.mount.d

: "Generate node-specific mount configuration"
cat > /etc/systemd/system/var-lib-rancher-rke2-agent-containerd-io.containerd.snapshotter.v1.zfs.mount.d/override.conf <<EOF
[Unit]
Before=rke2-${RKE2LAB_NODE_KIND}.service

[Mount]
What=tank/rke2/control-nodes/${RKE2LAB_NODE_NAME}/containerd

[Install]
RequiredBy=rke2-${RKE2LAB_NODE_KIND}.service
EOF

: "Reload systemd to recognize drop-in configuration"
systemctl daemon-reload
