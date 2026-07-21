#!/usr/bin/env -S bash -exuo pipefail

# Log all operations
exec > >(logger -t rke2-network-config) 2>&1

: "Load environment variables from mounted section manifests"
source /srv/host/systemd-scripts.d/rke2lab-env-load.sh

: "Load flox environment for yq and other tools"
set +x # Silence flox activation noise
source <(flox activate --dir /var/lib/cloud/seed/nocloud)
set -x

: "Resolve policy toggle for LAN binding (default enabled)"
lan_binding_enabled="${RKE2LAB_POLICY_NETWORK_LAN_BINDING_ENABLED:-true}"

: "Apply policy override when LAN binding is disabled"
case "${lan_binding_enabled,,}" in
1 | true | yes | on)
    # Canonical default: LAN binding remains enabled.
    rm -f /etc/netplan/90-rke2lab-lan-disable.yaml
    ;;
0 | false | no | off)
    cat >/etc/netplan/90-rke2lab-lan-disable.yaml <<'EOF'
network:
  version: 2
  ethernets:
    lan0:
      dhcp4: false
      dhcp6: false
      optional: true
      link-local: []
EOF
    ;;
*)
    echo "[!] Invalid RKE2LAB_POLICY_NETWORK_LAN_BINDING_ENABLED='${lan_binding_enabled}', expected boolean" >&2
    exit 1
    ;;
esac

: "=== Stopping dhcpcd for vmnet0 (systemd-networkd will manage it) ==="
# Kill dhcpcd processes for vmnet0 to prevent route conflicts
pkill -f 'dhcpcd.*vmnet0' || true

: "=== Applying netplan configuration ==="
if systemd-detect-virt --container >/dev/null 2>&1 || [[ -f /run/systemd/container ]]; then
    : "[i] Container environment detected; applying netplan via generate + networkd reload"
    netplan generate
    networkctl reload
    networkctl reconfigure lan0 vmnet0
else
    netplan apply
fi

: "=== Reloading systemd-networkd non-disruptively ==="
networkctl reload
networkctl reconfigure lan0 vmnet0
sleep 2

: "=== Verifying dhcpcd is not managing vmnet0 ==="
if ps aux | grep -v grep | grep 'dhcpcd.*vmnet0'; then
    echo "[!] Warning: dhcpcd still managing vmnet0, killing again..."
    pkill -f 'dhcpcd.*vmnet0' || true
else
    echo "[i] dhcpcd not managing vmnet0 (correct)"
fi

: "=== Final Network Status ==="
ip addr show
ip route show
systemctl status systemd-networkd --no-pager

: "=== Process List After Network Config ==="
ps -ef
