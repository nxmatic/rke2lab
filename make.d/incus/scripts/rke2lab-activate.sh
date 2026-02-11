#!/usr/bin/env -S bash -exu -o pipefail

source /srv/host/environment

: "Disable IPv6 system-wide"
sysctl -p /etc/sysctl.d/99-disable-ipv6.conf

: "Disable console.getty services to prevent conflicts with RKE2's console setup"
systemctl reset-failed console-getty.service 2>/dev/null || true
systemctl disable --now console-getty.target

: "Configure system-wide DNS"
ln -fs /run/systemd/resolve/resolv.conf /etc/resolv.conf

: "Run systemd link setup"
$RKE2LAB_SCRIPTS_DIR/rke2lab-systemd-link.sh

: "Start network configuration service immediately"
systemctl enable --now rke2lab-network-config.service

: "Install nix package manager for RKE2 package builds and runtime dependencies"
$RKE2LAB_SCRIPTS_DIR/rke2lab-nix-install.sh
$RKE2LAB_SCRIPTS_DIR/rke2lab-flox-install.sh

: "Enable RKE2 Lab target and all associated services"
systemctl enable --now rke2lab.target zfs-early-umount.service rke2lab-install.service

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
