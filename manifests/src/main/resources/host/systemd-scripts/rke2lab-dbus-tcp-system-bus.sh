#!/usr/bin/env -S bash -euo pipefail

# @codebase
# Enable DBus system bus TCP access on the master node for private-lab diagnostics.
# Canonical behavior only: unix socket remains active, tcp listener is added on vmnet0.

if [[ "${RKE2LAB_NODE_NAME:-}" != "master" ]]; then
	: "[rke2lab-dbus-tcp] skipping non-master node (${RKE2LAB_NODE_NAME:-unknown})"
	exit 0
fi

if ! command -v ip >/dev/null 2>&1; then
	echo "[rke2lab-dbus-tcp] missing required command: ip" >&2
	exit 1
fi

if ! command -v perl >/dev/null 2>&1; then
	echo "[rke2lab-dbus-tcp] missing required command: perl" >&2
	exit 1
fi

dbus_port="${RKE2LAB_DBUS_TCP_PORT:-12434}"
dbus_bind_address="${RKE2LAB_DBUS_TCP_BIND_ADDRESS:-}"

if [[ -z "${dbus_bind_address}" ]]; then
	dbus_bind_address="$(ip -o -4 addr show dev vmnet0 | awk '{print $4}' | cut -d/ -f1 | head -n1)"
fi

if [[ -z "${dbus_bind_address}" ]]; then
	echo "[rke2lab-dbus-tcp] unable to resolve vmnet0 ipv4 address" >&2
	exit 1
fi

dbus_socket_dropin_dir="/etc/systemd/system/dbus.socket.d"
dbus_socket_dropin_file="${dbus_socket_dropin_dir}/40-rke2lab-tcp.conf"
mkdir -p "${dbus_socket_dropin_dir}"

cat >"${dbus_socket_dropin_file}" <<EOF
[Socket]
ListenStream=
ListenStream=/run/dbus/system_bus_socket
ListenStream=${dbus_bind_address}:${dbus_port}
EOF

dbus_system_conf="/etc/dbus-1/system.conf"
if [[ ! -f "${dbus_system_conf}" ]]; then
	echo "[rke2lab-dbus-tcp] missing ${dbus_system_conf}" >&2
	exit 1
fi

if ! grep -q '<auth>ANONYMOUS</auth>' "${dbus_system_conf}"; then
	perl -0777 -i -pe 's{<auth>EXTERNAL</auth>}{<auth>EXTERNAL</auth>\n  <auth>ANONYMOUS</auth>\n  <allow_anonymous/>}s' "${dbus_system_conf}"
fi

if ! grep -q 'rke2lab-allow-all-policy' "${dbus_system_conf}"; then
	perl -0777 -i -pe 's{</busconfig>}{  <!-- rke2lab-allow-all-policy -->\n  <policy context="default">\n    <allow send_destination="*" eavesdrop="true"/>\n    <allow eavesdrop="true"/>\n    <allow own="*"/>\n  </policy>\n</busconfig>}s' "${dbus_system_conf}"
fi

systemctl daemon-reload
systemctl restart dbus.socket
systemctl restart dbus.service

echo "[rke2lab-dbus-tcp] enabled tcp listener on ${dbus_bind_address}:${dbus_port}"
