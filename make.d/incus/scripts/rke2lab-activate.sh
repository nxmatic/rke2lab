#!/usr/bin/env -S bash -exu -o pipefail

: "Disable IPv6 system-wide"
sysctl -p /etc/sysctl.d/99-disable-ipv6.conf

: "Configure system-wide DNS"
ln -fs /run/systemd/resolve/resolv.conf /etc/resolv.conf

: "Enable RKE2 systemd units"
systemctl enable \
	rke2lab-network-config.service \
	rke2lab-network-debug.service \
	rke2lab-network-wait.service \
	rke2lab-route-cleanup.service \
	zfs-early-umount.service \
	rke2lab-remount-shared.service \
	rke2lab-runtime-secrets.service \
	rke2lab-runtime-manifests.service \
	rke2lab-cilium-config-manifests.service \
	rke2lab-replication-manifests.service \
	rke2lab-mesh-secrets.service \
	rke2lab-gitops-secrets.service \
	rke2lab-gitops-manifests.service \
	rke2lab-storage-manifests.service \
	rke2lab-networking-manifests.service \
	rke2lab-mesh-manifests.service \
	rke2lab-cicd-secrets.service \
	rke2lab-tekton-pipelines-manifests.service

: "Start network configuration service immediately"
systemctl enable --now rke2lab-network-config.service

: "Start and wait for the RKE2 installation to complete"
systemctl enable --now rke2lab-install

: "Load the RKE2 environment"
source <( flox activate --dir /var/lib/rancher/rke2 )

: "Expose bind-mounted helper scripts on PATH (strip .sh suffix)"
scripts_dir=${RKE2LAB_SCRIPTS_DIR}
if [ -d "$scripts_dir" ]; then
  for src in "$scripts_dir"/*.sh; do
    [ -f "$src" ] || continue
    base=$(basename "${src%.sh}")
    ln -sf "$src" "/usr/local/sbin/$base"
  done
fi

: "Install and enable remaining systemd services"
rke2lab-enable-containerd-zfs-mount

: "Start the RKE2 service"
systemctl start --no-block rke2-${RKE2LAB_NODE_KIND}
