#!/usr/bin/env -S bash -exu -o pipefail

: "Load RKE2 environment and helper functions"
source "/srv/host/systemd-scripts.d/rke2lab-env-load.sh"
rke2lab::env:load

: "Ensure IPv6 is disabled and DNS is IPv4-only"
# Both configurations are baked into the disk image via incus-distrobuilder.yaml:
#   - /etc/sysctl.d/99-disable-ipv6.conf (applied at boot by systemd-sysctl)
#   - /etc/systemd/resolved.conf.d/00-ipv4-only-dns.conf (loaded by systemd-resolved)
# Just verify and restart resolved to ensure consistency
sysctl -p /etc/sysctl.d/99-disable-ipv6.conf 2>/dev/null || true
resolvectl flush-caches 2>/dev/null || true
systemctl restart systemd-resolved || true

: "Disable getty services to free up resources and avoid unnecessary log noise"
systemctl reset-failed console-getty.service 2>/dev/null || true
systemctl disable --now getty.target console-getty.service 2>/dev/null || true
systemctl mask systemd-rfkill.service 2>/dev/null || true
systemctl mask zfs-load-modules.service 2>/dev/null || true

: "Configure system-wide DNS"
ln -fs /run/systemd/resolve/resolv.conf /etc/resolv.conf

: "Run systemd link setup"
$RKE2LAB_SCRIPTS_DIR/rke2lab-systemd-link.sh

: "Start network configuration service immediately"
systemctl enable --now rke2lab-network.target

: "Enable RKE2 Lab tools target (Nix and Flox installation)"
systemctl enable --now rke2lab-tools.target

: "Enable RKE2 Lab target and all associated services"
systemctl enable --now rke2lab.target zfs-early-umount.service rke2lab-install.service

: "Load the RKE2 environment"
source <(flox activate --dir /var/lib/rancher/rke2)

: "Expose bind-mounted helper scripts on PATH (strip .sh suffix)"
scripts_dir=${RKE2LAB_SCRIPTS_DIR}
if [ -d "$scripts_dir" ]; then
	for src in "$scripts_dir"/*.sh; do
		[ -f "$src" ] || continue
		base=$(basename "${src%.sh}")
		ln -sf "$src" "/usr/local/sbin/$base"
	done
fi

: "Start the RKE2 service"
systemctl start --no-block rke2-${RKE2LAB_NODE_KIND}
